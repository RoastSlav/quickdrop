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
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Merges chunked file uploads into a single file asynchronously.
 *
 * <p>Each unique file upload (keyed by upload id) gets its own {@link MergeTask} running
 * on the shared thread pool. Chunks are enqueued as they arrive via
 * {@link #submitChunk(UploadRequest, MultipartFile, int)} and processed in order
 * by the task's {@link BlockingQueue}. Callers can either wait for the last chunk
 * to finish merging, or return immediately and poll {@link #getUploadStatus(String)}
 * for completion.
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
    private static final String CHUNK_DIR_NAME = ".upload-chunks";

    private final ConcurrentMap<String, MergeTask> mergeTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> abortedUploads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UploadCompletion> completedUploads = new ConcurrentHashMap<>();
    // Not a virtual-thread-per-task executor: the bounded pool + CallerRunsPolicy is
    // deliberate admission control (once full, the submitting Tomcat thread does the merge
    // itself, throttling the client). Virtual threads have no equivalent bound, so swapping
    // this would silently remove that backpressure.
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
    /** Last staging directory actually used, so the resolution outcome is logged on change rather than per chunk. */
    private final java.util.concurrent.atomic.AtomicReference<String> lastStagingDir =
            new java.util.concurrent.atomic.AtomicReference<>();

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
     * Resolves the current chunk-staging directory, re-reading {@code fileStoragePath} on
     * every call rather than caching it — the setting can change at runtime (admin edits
     * it with no restart required) and tests redirect it per-method via a fresh
     * {@code @TempDir}, neither of which a value computed once in the constructor would
     * ever observe, since this service is a Spring singleton constructed exactly once.
     * Mirrors the {@code Supplier<String>}-based live re-read {@link
     * org.rostislav.quickdrop.storage.LocalStorageService} already uses for the same reason.
     *
     * <p>NOT {@code System.getProperty("java.io.tmpdir")}: in a container that's typically
     * an unmounted, often tmpfs-backed path with no relation to how much real disk space is
     * actually available, so it can fail with ENOSPC on ordinary-sized uploads even when the
     * configured storage volume has plenty of room. {@code fileStoragePath} is only
     * consulted by {@code LocalStorageService} for final placement, but it's still the one
     * local, disk-backed directory every deployment is already expected to mount (see
     * README), so it's a safe place to stage chunks regardless of which backend is actually
     * active.
     *
     * <p>{@code getAbsoluteFile()} is required, not cosmetic: {@code fileStoragePath}
     * defaults to the relative string {@code "files"}, and {@code MultipartFile.transferTo
     * (File)} only does a direct file copy when the destination is absolute — for a relative
     * {@code File} it delegates to the Servlet API's {@code Part.write(String)}, which
     * resolves relative paths against the *container's* temp/work directory (e.g. Tomcat's
     * own {@code work/Tomcat/...} tree), not the app's working directory. A relative
     * directory here silently redirects every chunk write into that ephemeral location
     * instead of the real storage volume — {@code FileNotFoundException} on every upload.
     *
     * <p>Falls back to {@code java.io.tmpdir} (the long-standing behaviour, and always an
     * existing absolute directory) if the preferred location cannot be created or written
     * to — e.g. a read-only or restrictively-mounted volume. Staging must never be able to
     * take uploads down entirely just because the preferred directory is unavailable.
     */
    private File resolveTempDir() {
        File preferred = new File(applicationSettingsService.getFileStoragePath(), CHUNK_DIR_NAME).getAbsoluteFile();
        if (ensureUsableDirectory(preferred)) {
            noteStagingDir(preferred, null);
            return preferred;
        }

        File fallback = new File(System.getProperty("java.io.tmpdir"), CHUNK_DIR_NAME).getAbsoluteFile();
        if (ensureUsableDirectory(fallback)) {
            noteStagingDir(fallback, preferred);
            return fallback;
        }

        // Both unusable: return the OS temp dir itself, which always exists. The write will
        // surface a real error if even that fails, rather than us inventing a worse one.
        File lastResort = new File(System.getProperty("java.io.tmpdir")).getAbsoluteFile();
        noteStagingDir(lastResort, preferred);
        return lastResort;
    }

    /**
     * Creates {@code dir} if needed and confirms it is writable.
     *
     * <p>Uses {@link Files#createDirectories} rather than {@link File#mkdirs()} specifically
     * so a failure carries the real OS-level reason (permission denied, read-only file
     * system, no space left) instead of {@code mkdirs()}'s bare {@code false}, which
     * previously made a broken staging directory look identical to a working one right up
     * until every upload failed with a bare {@code FileNotFoundException}.
     */
    private boolean ensureUsableDirectory(File dir) {
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException e) {
            logger.error("Cannot create chunk staging directory {}: {}", dir, e.toString());
            return false;
        }
        if (!dir.isDirectory()) {
            logger.error("Chunk staging path {} exists but is not a directory", dir);
            return false;
        }
        if (!dir.canWrite()) {
            logger.error("Chunk staging directory {} is not writable", dir);
            return false;
        }
        return true;
    }

    /** Logs the staging location only when it changes, so this never spams once per chunk. */
    private void noteStagingDir(File chosen, File rejectedPreferred) {
        String previous = lastStagingDir.getAndSet(chosen.getPath());
        if (chosen.getPath().equals(previous)) {
            return;
        }
        if (rejectedPreferred == null) {
            logger.info("Staging upload chunks in {}", chosen);
        } else {
            logger.warn("Chunk staging directory {} is unusable; falling back to {}. " +
                    "Uploads will work, but large uploads are limited by that filesystem's free space.",
                    rejectedPreferred, chosen);
        }
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
        return submitChunk(request, multipartChunk, chunkNumber, true);
    }

    public Upload submitChunk(UploadRequest request, MultipartFile multipartChunk, int chunkNumber,
                              boolean waitForCompletion) throws IOException {
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
        if (isRecentlyAborted(taskKey)) {
            throw new UploadAbortedException("Upload was aborted");
        }

        // Attempt-unique filename: a resubmission of the same chunk number (network retry)
        // must never share an on-disk path with an earlier submission of that same chunk
        // number. If it did, enqueueChunk()'s dedup cleanup (which deletes the *duplicate's*
        // file) would actually delete the still-pending original's file out from under it,
        // since both ChunkInfo.chunkFile references would point at the same path -- the
        // original then hits FileNotFoundException when the drain loop finally reaches it,
        // aborting the whole upload. evictStaleTasks()'s cleanup glob matches on
        // startsWith(taskKey + "_chunk_"), so the added suffix doesn't break that.
        File savedChunk = new File(resolveTempDir(), taskKey + "_chunk_" + chunkNumber + "_" + UUID.randomUUID());
        multipartChunk.transferTo(savedChunk);
        logger.info("Chunk {} for file {} saved to {}", chunkNumber, request.fileName, savedChunk.getAbsolutePath());

        if (isRecentlyAborted(taskKey)) {
            deleteChunkFile(savedChunk);
            throw new UploadAbortedException("Upload was aborted");
        }

        MergeTask mergeTask = mergeTasks.computeIfAbsent(taskKey, key -> {
            MergeTask task = new MergeTask(request);
            // Fix 4: store the Future so eviction can cancel the thread
            task.future = executorService.submit(task);
            return task;
        });
        mergeTask.applyFolderMetadata(request);
        boolean isLastChunk = (chunkNumber == request.totalChunks - 1);
        if (!mergeTask.enqueueChunk(new ChunkInfo(chunkNumber, savedChunk, isLastChunk))) {
            throw new UploadAbortedException("Upload was aborted");
        }

        if (isLastChunk && waitForCompletion) {
            try {
                return mergeTask.getMergeCompletionFuture().get();
            } catch (InterruptedException e) {
                logger.error("Error waiting for merge completion: {}", e.getMessage());
                Thread.currentThread().interrupt();
                throw new IOException("Merge task interrupted", e);
            } catch (ExecutionException e) {
                logger.error("Error waiting for merge completion: {}", e.getMessage());
                throw new IOException("Merge task failed", e);
            }
        }
        return null;
    }

    public UploadProgress getUploadStatus(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return new UploadProgress("unknown", null, "Upload id is required", null, null);
        }

        String taskKey = uploadId.trim();
        UploadCompletion completion = completedUploads.get(taskKey);
        if (completion != null) {
            return completion.toProgress();
        }

        MergeTask task = mergeTasks.get(taskKey);
        if (task != null) {
            return new UploadProgress("processing", null, null, task.processedChunks, task.request.totalChunks);
        }

        if (isRecentlyAborted(taskKey)) {
            return new UploadProgress("aborted", null, "Upload was aborted", null, null);
        }

        return new UploadProgress("unknown", null, "Upload not found", null, null);
    }

    /**
     * Marks an upload as abandoned and requests cleanup of its merge task and temp chunks.
     *
     * @param uploadId stable per-upload identifier sent by the browser
     * @return {@code true} if a live task was found or any chunk files were removed
     */
    public boolean abortUpload(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return false;
        }

        String taskKey = uploadId.trim();
        abortedUploads.put(taskKey, Instant.now());
        MergeTask task = mergeTasks.remove(taskKey);
        if (task != null) {
            task.abort("client abort");
        }

        int deletedChunks = cleanUpChunks(taskKey);
        if (task == null) {
            logger.info("Abort requested for inactive upload {}; cleaned {} temporary chunk(s)",
                    taskKey, deletedChunks);
            return deletedChunks > 0;
        }

        logger.info("Abort requested for upload {} ({}); cleaned {} queued temporary chunk(s)",
                taskKey, task.request.fileName, deletedChunks);
        return true;
    }

    /**
     * Removes {@link MergeTask} entries whose last activity timestamp is older than
     * {@value #TASK_TTL_MINUTES} minutes. Called periodically by the TTL sweeper.
     */
    private void evictStaleTasks() {
        Instant threshold = Instant.now().minusSeconds(TASK_TTL_MINUTES * 60);
        Iterator<Map.Entry<String, MergeTask>> it = mergeTasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MergeTask> entry = it.next();
            MergeTask task = entry.getValue();
            if (task.lastActivityAt.isBefore(threshold)) {
                it.remove();
                abortedUploads.put(entry.getKey(), Instant.now());
                task.abort("inactivity timeout");
                int deletedChunks = cleanUpChunks(entry.getKey(), task.request.totalChunks);
                logger.warn("Evicted stale upload {} ({}) after {} minutes of inactivity; cleaned {} temporary chunk(s)",
                        entry.getKey(), task.request.fileName, TASK_TTL_MINUTES, deletedChunks);
            }
        }
        abortedUploads.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        completedUploads.entrySet().removeIf(entry -> entry.getValue().completedAt().isBefore(threshold));
    }

    /**
     * Deletes all temporary chunk files created for a given upload request.
     *
     * @param request the upload request whose chunks should be removed
     */
    private int cleanUpChunks(UploadRequest request) {
        return cleanUpChunks(getTaskKey(request), request.totalChunks);
    }

    private int cleanUpChunks(String taskKey) {
        return cleanUpChunks(taskKey, -1);
    }

    private int cleanUpChunks(String taskKey, int totalChunks) {
        // totalChunks is unused: chunk files are named taskKey + "_chunk_" + chunkNumber
        // + "_" + a per-attempt random UUID (see submitChunk()'s comment on why), so an
        // exact-name reconstruction like "taskKey + "_chunk_" + i" can never match a real
        // file on disk and silently deletes nothing -- a prefix glob is the only naming
        // scheme that actually works here, regardless of whether totalChunks is known.
        int deleted = 0;
        int failed = 0;

        File[] chunkFiles = resolveTempDir().listFiles((dir, name) -> name.startsWith(taskKey + "_chunk_"));
        if (chunkFiles != null) {
            for (File chunkFile : chunkFiles) {
                DeleteResult result = deleteChunkFile(chunkFile);
                if (result == DeleteResult.DELETED) {
                    deleted++;
                } else if (result == DeleteResult.FAILED) {
                    failed++;
                }
            }
        }

        if (deleted > 0 || failed > 0) {
            logger.info("Cleaned up {} temporary chunk(s) for upload {} ({} failed)",
                    deleted, taskKey, failed);
        }
        return deleted;
    }

    private boolean isRecentlyAborted(String taskKey) {
        Instant abortedAt = abortedUploads.get(taskKey);
        if (abortedAt == null) {
            return false;
        }
        Instant threshold = Instant.now().minusSeconds(TASK_TTL_MINUTES * 60);
        if (abortedAt.isBefore(threshold)) {
            abortedUploads.remove(taskKey, abortedAt);
            return false;
        }
        return true;
    }

    private DeleteResult deleteChunkFile(File chunkFile) {
        if (!chunkFile.exists()) {
            return DeleteResult.MISSING;
        }
        if (chunkFile.delete()) {
            return DeleteResult.DELETED;
        }
        logger.warn("Failed to delete chunk file: {}", chunkFile.getAbsolutePath());
        return DeleteResult.FAILED;
    }

    private String getTaskKey(UploadRequest request) {
        return (request.uploadId != null && !request.uploadId.isBlank())
                ? request.uploadId
                : request.fileName;
    }

    private enum DeleteResult {
        DELETED,
        FAILED,
        MISSING
    }

    public record UploadProgress(String status, String uuid, String error,
                                 Integer processedChunks, Integer totalChunks) {
    }

    private record UploadCompletion(Instant completedAt, String status, String uuid, String error) {
        static UploadCompletion complete(Upload upload) {
            return new UploadCompletion(Instant.now(), "complete", upload.uuid, null);
        }

        static UploadCompletion failed(String error) {
            return new UploadCompletion(Instant.now(), "failed", null, error);
        }

        static UploadCompletion aborted() {
            return new UploadCompletion(Instant.now(), "aborted", null, "Upload was aborted");
        }

        UploadProgress toProgress() {
            return new UploadProgress(status, uuid, error, null, null);
        }
    }

    /**
     * Worker that reads {@link ChunkInfo} items from a blocking queue and streams
     * them sequentially into the final output file, encrypting if configured.
     * Completes {@link #mergeCompletionFuture} with the saved {@link Upload}.
     */
    private class MergeTask implements Runnable {
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
        private volatile int processedChunks = 0;
        private String uuid;
        private volatile boolean aborted;
        private volatile Instant lastActivityAt = Instant.now();

        MergeTask(UploadRequest request) {
            this.request = request;
            do {
                uuid = UUID.randomUUID().toString();
            } while (uploadRepository.findByUUID(uuid).isPresent());
        }

        public boolean enqueueChunk(ChunkInfo chunkInfo) {
            lastActivityAt = Instant.now();
            if (aborted) {
                deleteChunkFile(chunkInfo.chunkFile);
                return false;
            }
            // Set.add()'s return value is an atomic check-and-mark. The file delete (disk I/O)
            // runs after releasing the lock, not inside it -- holding a monitor across
            // blocking I/O is a virtual-thread pinning hazard.
            boolean isDuplicate;
            synchronized (receivedChunks) {
                isDuplicate = !receivedChunks.add(chunkInfo.chunkNumber);
            }
            if (isDuplicate) {
                logger.warn("Duplicate chunk {} for file {} — dropping and deleting temp file",
                        chunkInfo.chunkNumber, request.fileName);
                if (!chunkInfo.chunkFile.delete()) {
                    logger.warn("Failed to delete duplicate chunk file: {}", chunkInfo.chunkFile.getAbsolutePath());
                }
                return true;
            }
            if (aborted) {
                deleteChunkFile(chunkInfo.chunkFile);
                return false;
            }
            queue.add(chunkInfo);
            return true;
        }

        public void applyFolderMetadata(UploadRequest latestRequest) {
            if (!latestRequest.folderUpload) {
                return;
            }
            synchronized (request) {
                request.folderUpload = true;
                if (latestRequest.folderName != null && !latestRequest.folderName.isBlank()) {
                    request.folderName = latestRequest.folderName;
                }
                if (latestRequest.folderManifest != null && !latestRequest.folderManifest.isBlank()) {
                    request.folderManifest = latestRequest.folderManifest;
                }
            }
        }

        public CompletableFuture<Upload> getMergeCompletionFuture() {
            return mergeCompletionFuture;
        }

        public void abort(String reason) {
            aborted = true;
            mergeCompletionFuture.completeExceptionally(new CancellationException("Upload aborted: " + reason));
            Future<?> f = future;
            if (f != null) {
                f.cancel(true);
            }
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

                if (aborted) {
                    throw new IOException("Upload was aborted before saving");
                }

                Upload upload = fileLifecycleService.saveFile(request, uuid);
                if (upload == null) {
                    throw new IOException("Saving file " + request.fileName + " failed");
                }

                logger.info("File {} saved successfully with UUID {}", request.fileName, upload.uuid);
                completedUploads.put(getTaskKey(request), UploadCompletion.complete(upload));
                mergeCompletionFuture.complete(upload);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (aborted) {
                    logger.info("Upload {} ({}) was aborted before completion", getTaskKey(request), request.fileName);
                    completedUploads.put(getTaskKey(request), UploadCompletion.aborted());
                } else {
                    logger.error("Error merging chunks for file {}: {}", request.fileName, e.getMessage());
                    completedUploads.put(getTaskKey(request), UploadCompletion.failed(e.getMessage()));
                }
                mergeCompletionFuture.completeExceptionally(e);
                cleanUpPartialStorageObject();
                int deletedChunks = cleanUpChunks(request);
                logger.info("Upload cleanup for {} ({}) removed {} temporary chunk(s)",
                        getTaskKey(request), request.fileName, deletedChunks);
            } finally {
                mergeTasks.remove(getTaskKey(request));
            }
        }

        private void cleanUpPartialStorageObject() {
            if (uuid == null) {
                return;
            }
            boolean existed = storageService.exists(uuid);
            if (storageService.delete(uuid)) {
                if (!existed) {
                    logger.info("No committed partial storage object found for upload {}", request.fileName);
                    return;
                }
                logger.info("Removed partial storage object {} for upload {}", uuid, request.fileName);
            } else {
                logger.warn("Failed to remove partial storage object {} for upload {}", uuid, request.fileName);
            }
        }
    }
}
