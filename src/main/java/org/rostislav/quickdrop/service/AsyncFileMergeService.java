package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.ChunkInfo;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Merges chunked file uploads into a single file asynchronously.
 *
 * <p>Each unique file upload (keyed by filename) gets its own {@link MergeTask} running
 * on the shared thread pool. Chunks are enqueued as they arrive via
 * {@link #submitChunk(UploadRequest, MultipartFile, int)} and processed in order
 * by the task's {@link BlockingQueue}. When the caller submits the last chunk it
 * blocks on the {@link CompletableFuture} until the merge and database save complete,
 * then returns the saved {@link Upload} (a {@link org.rostislav.quickdrop.entity.StoredFile}
 * or {@link org.rostislav.quickdrop.entity.Paste} instance).
 *
 * <p>The thread pool uses {@link ThreadPoolExecutor.CallerRunsPolicy}.
 * A background TTL sweeper runs every {@value #TASK_TTL_MINUTES} minutes to evict
 * entries for uploads that were abandoned before the last chunk arrived.
 */
@Service
public class AsyncFileMergeService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncFileMergeService.class);
    private static final int MAX_CONCURRENT_MERGES = 20;
    private static final long TASK_TTL_MINUTES = 60;
    // Fix 3: cap totalChunks to prevent DoS via Integer.MAX_VALUE
    private static final int MAX_TOTAL_CHUNKS = 10_000;

    private final ConcurrentMap<String, MergeTask> mergeTasks = new ConcurrentHashMap<>();
    private final ExecutorService executorService = new ThreadPoolExecutor(
            2, MAX_CONCURRENT_MERGES,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_CONCURRENT_MERGES),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private final ScheduledExecutorService ttlSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "merge-task-ttl-sweeper");
        t.setDaemon(true);
        return t;
    });
    private final ApplicationSettingsService applicationSettingsService;
    private final EncryptionService encryptionService;
    private final FileQueryService fileQueryService;
    private final FileLifecycleService fileLifecycleService;
    private final UploadRepository uploadRepository;
    private final StorageService storageService;
    private final File tempDir = new File(System.getProperty("java.io.tmpdir"));

    public AsyncFileMergeService(ApplicationSettingsService applicationSettingsService,
                                 EncryptionService encryptionService,
                                 FileQueryService fileQueryService,
                                 FileLifecycleService fileLifecycleService,
                                 UploadRepository uploadRepository,
                                 StorageService storageService) {
        this.applicationSettingsService = applicationSettingsService;
        this.encryptionService = encryptionService;
        this.fileQueryService = fileQueryService;
        this.fileLifecycleService = fileLifecycleService;
        this.uploadRepository = uploadRepository;
        this.storageService = storageService;
        ttlSweeper.scheduleAtFixedRate(this::evictStaleTasks, TASK_TTL_MINUTES, TASK_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Saves a chunk to the temp directory and enqueues it for merging.
     *
     * <p>A {@link MergeTask} is created for the upload on the first chunk and reused for
     * subsequent chunks. When the last chunk (index {@code totalChunks - 1}) is submitted
     * the caller blocks until the merge thread finishes writing and saving the file, and
     * the resulting {@link Upload} is returned. For non-final chunks {@code null} is
     * returned immediately.
     *
     * @param request        metadata for the upload (filename, total chunk count, password, etc.)
     * @param multipartChunk the chunk bytes received from the HTTP request
     * @param chunkNumber    zero-based chunk index
     * @return the saved {@link Upload} after the last chunk, or {@code null} for intermediate chunks
     * @throws IOException if saving the chunk to disk or waiting on the merge future fails
     */
    public Upload submitChunk(UploadRequest request, MultipartFile multipartChunk, int chunkNumber) throws IOException {
        // Fix 3: reject absurdly large totalChunks to prevent DoS
        if (request.totalChunks > MAX_TOTAL_CHUNKS) {
            throw new IllegalArgumentException(
                    "totalChunks " + request.totalChunks + " exceeds the maximum allowed value of " + MAX_TOTAL_CHUNKS);
        }

        // Use uploadId (a UUID) as the temp-file prefix — never the user-supplied filename,
        // which could contain path-traversal sequences or OS-reserved characters.
        String taskKey = (request.uploadId != null && !request.uploadId.isBlank())
                ? request.uploadId
                : request.fileName;
        File savedChunk = new File(tempDir, taskKey + "_chunk_" + chunkNumber);
        multipartChunk.transferTo(savedChunk);
        logger.info("Chunk {} for file {} saved to {}", chunkNumber, request.fileName, savedChunk.getAbsolutePath());

        MergeTask mergeTask = mergeTasks.computeIfAbsent(taskKey, key -> {
            MergeTask task = new MergeTask(request);
            // Fix 4: store the Future so eviction can cancel the thread
            task.future = executorService.submit(task);
            return task;
        });
        boolean isLastChunk = (chunkNumber == request.totalChunks - 1);
        mergeTask.enqueueChunk(new ChunkInfo(chunkNumber, savedChunk, isLastChunk));

        if (isLastChunk) {
            try {
                return mergeTask.getMergeCompletionFuture().get();
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error waiting for merge completion: {}", e.getMessage());
                Thread.currentThread().interrupt();
                throw new IOException("Merge task interrupted", e);
            }
        }
        return null;
    }

    /**
     * Removes {@link MergeTask} entries whose creation timestamp is older than
     * {@value #TASK_TTL_MINUTES} minutes. Called periodically by the TTL sweeper.
     */
    private void evictStaleTasks() {
        Instant threshold = Instant.now().minusSeconds(TASK_TTL_MINUTES * 60);
        Iterator<Map.Entry<String, MergeTask>> it = mergeTasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MergeTask> entry = it.next();
            if (entry.getValue().createdAt.isBefore(threshold)) {
                logger.warn("Evicting stale merge task for file: {}", entry.getKey());
                // Fix 4: cancel the background thread when evicting
                Future<?> f = entry.getValue().future;
                if (f != null) {
                    f.cancel(true);
                }
                it.remove();
            }
        }
    }

    /**
     * Deletes all temporary chunk files created for a given upload request.
     *
     * @param request the upload request whose chunks should be removed
     */
    private void cleanUpChunks(UploadRequest request) {
        String taskKey = (request.uploadId != null && !request.uploadId.isBlank())
                ? request.uploadId
                : request.fileName;
        for (int i = 0; i < request.totalChunks; i++) {
            File chunkFile = new File(tempDir, taskKey + "_chunk_" + i);
            if (chunkFile.exists() && !chunkFile.delete()) {
                logger.warn("Failed to delete chunk file: {}", chunkFile.getAbsolutePath());
            }
            logger.info("Cleaning up chunk {}", i);
        }
    }

    /**
     * Worker that reads {@link ChunkInfo} items from a blocking queue and streams
     * them sequentially into the final output file, encrypting if configured.
     * Completes {@link #mergeCompletionFuture} with the saved {@link Upload}.
     */
    private class MergeTask implements Runnable {
        final Instant createdAt = Instant.now();
        // Inter-thread signalling: producer parks new chunks here.
        private final BlockingQueue<ChunkInfo> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<Upload> mergeCompletionFuture = new CompletableFuture<>();
        private final UploadRequest request;
        // Fix 4: held so eviction can cancel this thread.
        volatile Future<?> future;
        // Fix 1: ordered assembly — TreeMap keyed by chunkNumber.
        private final TreeMap<Integer, ChunkInfo> pendingChunks = new TreeMap<>();
        // Fix 2: dedup tracker — prevents duplicate queue entries on retry.
        private final Set<Integer> receivedChunks = new HashSet<>();
        private int nextExpectedChunk = 0;
        private int processedChunks = 0;
        private String uuid;

        MergeTask(UploadRequest request) {
            this.request = request;
            do {
                uuid = UUID.randomUUID().toString();
            } while (uploadRepository.findByUUID(uuid).isPresent());
        }

        public void enqueueChunk(ChunkInfo chunkInfo) {
            // Fix 2: deduplicate retried chunks — drop duplicates before queuing.
            synchronized (receivedChunks) {
                if (receivedChunks.contains(chunkInfo.chunkNumber)) {
                    logger.warn("Duplicate chunk {} for file {} — dropping and deleting temp file",
                            chunkInfo.chunkNumber, request.fileName);
                    if (!chunkInfo.chunkFile.delete()) {
                        logger.warn("Failed to delete duplicate chunk file: {}", chunkInfo.chunkFile.getAbsolutePath());
                    }
                    return;
                }
                receivedChunks.add(chunkInfo.chunkNumber);
            }
            queue.add(chunkInfo);
        }

        public CompletableFuture<Upload> getMergeCompletionFuture() {
            return mergeCompletionFuture;
        }

        @Override
        public void run() {
            boolean shouldEncrypt = fileQueryService.shouldEncrypt(request);
            try {
                OutputStream baseOut = storageService.getOutputStream(uuid);
                OutputStream finalOut = shouldEncrypt
                        ? encryptionService.getEncryptedOutputStream(baseOut, request.password)
                        : new BufferedOutputStream(baseOut);

                try (finalOut) {
                    // Fix 1: ordered write loop — park chunks in TreeMap, write in sequence.
                    while (processedChunks < request.totalChunks) {
                        ChunkInfo info = queue.take();
                        pendingChunks.put(info.chunkNumber, info);

                        // Drain all consecutive chunks starting at nextExpectedChunk.
                        while (pendingChunks.containsKey(nextExpectedChunk)) {
                            ChunkInfo toWrite = pendingChunks.remove(nextExpectedChunk);
                            try (InputStream in = new BufferedInputStream(new FileInputStream(toWrite.chunkFile))) {
                                in.transferTo(finalOut);
                            }
                            if (!toWrite.chunkFile.delete()) {
                                logger.warn("Failed to delete chunk file: {}", toWrite.chunkFile.getAbsolutePath());
                            }
                            processedChunks++;
                            logger.info("Merged chunk {} for file {}", toWrite.chunkNumber, request.fileName);
                            nextExpectedChunk++;
                        }
                    }
                }
                logger.info("All {} chunks merged for file {}", request.totalChunks, request.fileName);

                Upload upload = fileLifecycleService.saveFile(request, uuid);
                if (upload != null) {
                    logger.info("File {} saved successfully with UUID {}", request.fileName, upload.uuid);
                } else {
                    logger.error("Saving file {} failed", request.fileName);
                }
                mergeCompletionFuture.complete(upload);
            } catch (Exception e) {
                logger.error("Error merging chunks for file {}: {}", request.fileName, e.getMessage());
                mergeCompletionFuture.completeExceptionally(e);
                cleanUpChunks(request);
            } finally {
                String taskKey = (request.uploadId != null && !request.uploadId.isBlank())
                        ? request.uploadId
                        : request.fileName;
                mergeTasks.remove(taskKey);
            }
        }
    }
}
