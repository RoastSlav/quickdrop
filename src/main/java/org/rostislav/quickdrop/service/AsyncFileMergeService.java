package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.ChunkInfo;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
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
    private final File tempDir = new File(System.getProperty("java.io.tmpdir"));

    public AsyncFileMergeService(ApplicationSettingsService applicationSettingsService,
                                 EncryptionService encryptionService,
                                 FileQueryService fileQueryService,
                                 FileLifecycleService fileLifecycleService,
                                 UploadRepository uploadRepository) {
        this.applicationSettingsService = applicationSettingsService;
        this.encryptionService = encryptionService;
        this.fileQueryService = fileQueryService;
        this.fileLifecycleService = fileLifecycleService;
        this.uploadRepository = uploadRepository;
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
        File savedChunk = new File(tempDir, request.fileName + "_chunk_" + chunkNumber);
        multipartChunk.transferTo(savedChunk);
        logger.info("Chunk {} for file {} saved to {}", chunkNumber, request.fileName, savedChunk.getAbsolutePath());

        MergeTask mergeTask = mergeTasks.computeIfAbsent(request.fileName, key -> {
            MergeTask task = new MergeTask(request);
            executorService.submit(task);
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
        for (int i = 0; i < request.totalChunks; i++) {
            File chunkFile = new File(tempDir, request.fileName + "_chunk_" + i);
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
        private final BlockingQueue<ChunkInfo> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<Upload> mergeCompletionFuture = new CompletableFuture<>();
        private final UploadRequest request;
        private int processedChunks = 0;
        private String uuid;

        MergeTask(UploadRequest request) {
            this.request = request;
            do {
                uuid = UUID.randomUUID().toString();
            } while (uploadRepository.findByUUID(uuid).isPresent());
        }

        public void enqueueChunk(ChunkInfo chunkInfo) {
            queue.add(chunkInfo);
        }

        public CompletableFuture<Upload> getMergeCompletionFuture() {
            return mergeCompletionFuture;
        }

        @Override
        public void run() {
            File finalFile = Paths.get(applicationSettingsService.getFileStoragePath(), uuid).toFile();

            try (OutputStream finalOut = fileQueryService.shouldEncrypt(request) ?
                    encryptionService.getEncryptedOutputStream(finalFile, request.password) :
                    new BufferedOutputStream(new FileOutputStream(finalFile, true))) {

                while (processedChunks < request.totalChunks) {
                    ChunkInfo info = queue.take();
                    try (InputStream in = new BufferedInputStream(new FileInputStream(info.chunkFile))) {
                        in.transferTo(finalOut);
                    }

                    if (!info.chunkFile.delete()) {
                        logger.warn("Failed to delete chunk file: {}", info.chunkFile.getAbsolutePath());
                    }

                    processedChunks++;
                    logger.info("Merged chunk {} for file {}", info.chunkNumber, request.fileName);
                    if (info.isLastChunk) {
                        break;
                    }
                }
                logger.info("All {} chunks merged for file {}", request.totalChunks, request.fileName);

                Upload upload = fileLifecycleService.saveFile(finalFile, request, uuid);
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
                mergeTasks.remove(request.fileName);
            }
        }
    }
}
