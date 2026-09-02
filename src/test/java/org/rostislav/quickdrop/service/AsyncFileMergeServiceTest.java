package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.storage.StorageService;
import org.rostislav.quickdrop.support.FixtureFiles;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link AsyncFileMergeService} against the real Spring context,
 * with storage redirected to a {@code @TempDir} by {@link QuickdropIntegrationTest}.
 */
class AsyncFileMergeServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private AsyncFileMergeService asyncFileMergeService;
    @Autowired
    private StorageService storageService;

    private static UploadRequest baseRequest(String uploadId, String fileName, int totalChunks, long fileSize) {
        UploadRequest request = new UploadRequest(null, false, null, false,
                fileName, totalChunks, fileSize, "1.2.3.4", "JUnit-Agent",
                false, null, null, false);
        request.uploadId = uploadId;
        return request;
    }

    private static MockMultipartFile chunk(byte[] data) {
        return new MockMultipartFile("file", "chunk", "application/octet-stream", data);
    }

    private byte[] readStoredBytes(String uuid) throws Exception {
        try (InputStream in = storageService.getInputStream(uuid)) {
            return in.readAllBytes();
        }
    }

    /**
     * uploadId becomes part of an on-disk filename, so a {@code ..} sequence in it once
     * wrote attacker-controlled bytes anywhere the process could reach. Declaring more
     * chunks than are sent keeps the merge from ever consuming the planted file, and the
     * TTL cleanup only globs the staging directory, so a file placed outside it would
     * persist indefinitely.
     */
    @Test
    @Timeout(60)
    void traversalUploadIdIsRejectedAndWritesNothingOutsideStagingDir() throws Exception {
        File outsideDir = storageDir.resolve("outside").toFile();
        assertTrue(outsideDir.mkdirs(), "probe directory should be created");

        UploadRequest request = baseRequest("../outside/pwned", "harmless.txt", 2, 18);

        assertThrows(IllegalArgumentException.class,
                () -> asyncFileMergeService.submitChunk(request, chunk("* * * * * root id".getBytes(StandardCharsets.UTF_8)), 0, false),
                "a traversal uploadId must be rejected outright");

        File[] planted = outsideDir.listFiles();
        assertTrue(planted == null || planted.length == 0,
                "nothing may be written outside the staging directory, found: " + Arrays.toString(planted));
    }

    @Test
    @Timeout(60)
    void blankUploadIdIsRejectedRatherThanFallingBackToFileName() {
        UploadRequest request = baseRequest(null, "../../evil.txt", 1, 4);

        assertThrows(IllegalArgumentException.class,
                () -> asyncFileMergeService.submitChunk(request, chunk("data".getBytes(StandardCharsets.UTF_8)), 0, false),
                "a blank uploadId must not fall back to the attacker-supplied fileName");
    }

    @Test
    @Timeout(60)
    void fullRoundTripAcrossChunkBoundaryIsByteExact() throws Exception {
        byte[] payload = FixtureFiles.payloadCrossingChunkBoundary(); // 9 MB, 3 chunks: 4+4+1 MB
        String expectedHash = FixtureFiles.sha256Hex(payload);
        String uploadId = UUID.randomUUID().toString();
        int chunkSize = FixtureFiles.UPLOAD_CHUNK_SIZE;
        int totalChunks = (int) Math.ceil(payload.length / (double) chunkSize);
        assertEquals(3, totalChunks, "fixture payload should split into exactly 3 chunks");

        UploadRequest request = baseRequest(uploadId, "big-file.bin", totalChunks, payload.length);

        Upload result = null;
        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, payload.length);
            byte[] chunkData = Arrays.copyOfRange(payload, start, end);
            Upload intermediate = asyncFileMergeService.submitChunk(request, chunk(chunkData), i);
            if (i < totalChunks - 1) {
                assertNull(intermediate, "intermediate chunks must return null -- the frontend depends on this");
            } else {
                result = intermediate;
            }
        }

        assertNotNull(result, "final chunk must return the saved Upload");
        assertNotNull(result.uuid);
        assertNotEquals(uploadId, result.uuid, "the server-assigned file UUID is distinct from the client upload session id");

        byte[] storedBytes = readStoredBytes(result.uuid);
        assertEquals(expectedHash, FixtureFiles.sha256Hex(storedBytes),
                "reassembled file content must be byte-exact with the original upload");
    }

    @Test
    @Timeout(30)
    void outOfOrderChunkSubmissionStillAssemblesInCorrectSequence() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "ordered.txt", 3, 9);

        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false);
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);
        Upload result = asyncFileMergeService.submitChunk(request, chunk("CCC".getBytes(StandardCharsets.UTF_8)), 2, true);

        assertNotNull(result);
        byte[] storedBytes = readStoredBytes(result.uuid);
        assertEquals("AAABBBCCC", new String(storedBytes, StandardCharsets.UTF_8),
                "chunks must be written in index order regardless of arrival order");
    }

    @Test
    @Timeout(30)
    void duplicateChunkSubmissionIsDroppedNotDuplicated() throws Exception {
        // Regression guard: temp chunk files are named with a per-attempt-unique UUID suffix, not just chunk number.
        // Previously a duplicate submission of a still-pending chunk deleted the same on-disk file the pending
        // original would later read, throwing FileNotFoundException and failing the whole merge task.
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "dup.txt", 3, 9);

        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false);
        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false); // duplicate, should be dropped harmlessly
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);
        Upload result = asyncFileMergeService.submitChunk(request, chunk("CCC".getBytes(StandardCharsets.UTF_8)), 2, true);

        assertNotNull(result, "the upload must complete despite the duplicate submission");
        byte[] storedBytes = readStoredBytes(result.uuid);
        assertEquals("AAABBBCCC", new String(storedBytes, StandardCharsets.UTF_8),
                "a duplicate chunk submission must not be written twice, and must not destroy the still-pending original");
    }

    @Test
    @Timeout(30)
    void abortUploadCleansUpTempChunkFilesFromDisk() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "aborted.bin", 3, 30);

        // Chunk index 1 (not first-expected) stays parked, un-drained, rather than immediately written and deleted.
        asyncFileMergeService.submitChunk(request, chunk(FixtureFiles.deterministicBytes(10, 1L)), 1, false);

        boolean aborted = asyncFileMergeService.abortUpload(uploadId);
        assertTrue(aborted);

        AsyncFileMergeService.UploadProgress status = asyncFileMergeService.getUploadStatus(uploadId);
        assertEquals("aborted", status.status());

        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        long deadline = System.currentTimeMillis() + 5000;
        File[] leftover;
        do {
            leftover = tempDir.listFiles((dir, name) -> name.startsWith(uploadId + "_chunk_"));
            if (leftover == null || leftover.length == 0) break;
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);

        assertTrue(leftover == null || leftover.length == 0,
                "abortUpload must remove all temp chunk files for the upload: " +
                        (leftover == null ? "" : Arrays.toString(leftover)));
    }

    @Test
    void abortUploadOfUnknownUploadIdReturnsFalse() {
        assertFalse(asyncFileMergeService.abortUpload(UUID.randomUUID().toString()));
    }

    @Test
    void abortUploadWithBlankIdReturnsFalse() {
        assertFalse(asyncFileMergeService.abortUpload(""));
        assertFalse(asyncFileMergeService.abortUpload(null));
    }

    @Test
    @Timeout(30)
    void chunksSubmittedAfterAbortAreRejected() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "rejected.bin", 2, 6);

        asyncFileMergeService.abortUpload(uploadId);

        assertThrows(java.io.IOException.class, () ->
                asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false));
    }

    @Test
    void getUploadStatusIsUnknownForUnsubmittedId() {
        AsyncFileMergeService.UploadProgress status = asyncFileMergeService.getUploadStatus(UUID.randomUUID().toString());
        assertEquals("unknown", status.status());
    }

    @Test
    void getUploadStatusIsUnknownForBlankId() {
        assertEquals("unknown", asyncFileMergeService.getUploadStatus("").status());
        assertEquals("unknown", asyncFileMergeService.getUploadStatus(null).status());
    }

    @Test
    @Timeout(30)
    void getUploadStatusReportsProcessingThenComplete() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "progress.bin", 3, 30);

        asyncFileMergeService.submitChunk(request, chunk(FixtureFiles.deterministicBytes(10, 2L)), 0, false);

        long deadline = System.currentTimeMillis() + 5000;
        AsyncFileMergeService.UploadProgress status;
        do {
            status = asyncFileMergeService.getUploadStatus(uploadId);
            if ("processing".equals(status.status())) break;
            Thread.sleep(30);
        } while (System.currentTimeMillis() < deadline);

        assertEquals("processing", status.status());
        assertEquals(3, status.totalChunks());

        asyncFileMergeService.submitChunk(request, chunk(FixtureFiles.deterministicBytes(10, 3L)), 1, false);
        Upload result = asyncFileMergeService.submitChunk(request, chunk(FixtureFiles.deterministicBytes(10, 4L)), 2, true);
        assertNotNull(result);

        AsyncFileMergeService.UploadProgress finalStatus = asyncFileMergeService.getUploadStatus(uploadId);
        assertEquals("complete", finalStatus.status());
        assertEquals(result.uuid, finalStatus.uuid());
    }

    @Test
    void submitChunkRejectsAbsurdTotalChunks() {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "huge.bin", Integer.MAX_VALUE, 1);

        assertThrows(IllegalArgumentException.class, () ->
                asyncFileMergeService.submitChunk(request, chunk("x".getBytes(StandardCharsets.UTF_8)), 0, false));
    }

    // evictStaleTasks() is private and normally runs on a schedule; reflection invokes it directly and backdates
    // lastActivityAt, since waiting out the real TASK_TTL_MINUTES isn't practical.
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeTasksField() {
        return (Map<String, Object>) ReflectionTestUtils.getField(asyncFileMergeService, "mergeTasks");
    }

    @Test
    @Timeout(30)
    void evictStaleTasks_removesTaskInactiveBeyondTtl() throws Exception {
        // Chunk file cleanup is already covered by abortUploadCleansUpTempChunkFilesFromDisk; asserting it again here
        // would race the background MergeTask thread under Windows file-locking. This focuses on the TTL sweep itself.
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "stale.bin", 3, 30);
        asyncFileMergeService.submitChunk(request, chunk(FixtureFiles.deterministicBytes(10, 7L)), 1, false);

        Object task = mergeTasksField().get(uploadId);
        assertNotNull(task, "merge task must be registered under the upload id");
        ReflectionTestUtils.setField(task, "lastActivityAt", Instant.now().minusSeconds(61 * 60));

        ReflectionTestUtils.invokeMethod(asyncFileMergeService, "evictStaleTasks");

        assertFalse(mergeTasksField().containsKey(uploadId), "stale task must be removed from the active-tasks map");
        AsyncFileMergeService.UploadProgress status = asyncFileMergeService.getUploadStatus(uploadId);
        assertEquals("aborted", status.status(), "an evicted task must report as aborted, same as an explicit abort");
    }

    @Test
    void evictStaleTasks_leavesRecentlyActiveTaskAlone() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "fresh.bin", 3, 30);
        asyncFileMergeService.submitChunk(request, chunk(FixtureFiles.deterministicBytes(10, 8L)), 1, false);

        try {
            ReflectionTestUtils.invokeMethod(asyncFileMergeService, "evictStaleTasks");

            assertTrue(mergeTasksField().containsKey(uploadId), "a recently active task must not be evicted");
        } finally {
            // Chunk 0 was deliberately never sent; without this cleanup the background MergeTask leaks for the life of the JVM.
            asyncFileMergeService.abortUpload(uploadId);
        }
    }

    @Test
    @Timeout(30)
    void submitChunk_waitForCompletionInterrupted_wrapsAsIOException() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        // Chunk 0 (first-expected) is deliberately never sent, so the completion future stays genuinely pending -- deterministic, not a timing race.
        UploadRequest request = baseRequest(uploadId, "interrupt-me.bin", 2, 6);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, true);
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        waiter.start();
        // Not needed for correctness (the future can't complete on its own); just gives the thread a chance to reach the blocking get() before interrupt().
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(10_000);

        try {
            assertFalse(waiter.isAlive(), "waiter thread must have exited after being interrupted");
            assertNotNull(caught.get(), "submitChunk must have thrown");
            assertInstanceOf(java.io.IOException.class, caught.get());
            assertEquals("Merge task interrupted", caught.get().getMessage());
            assertInstanceOf(InterruptedException.class, caught.get().getCause());
        } finally {
            // Interrupting the waiter only unblocks its own get() call; the background MergeTask is still alive waiting for chunk 0, which was never sent, and would otherwise leak for the life of the JVM.
            asyncFileMergeService.abortUpload(uploadId);
        }
    }

    @Test
    @Timeout(30)
    void submitChunk_waitForCompletionOnExceptionallyCompletedFuture_wrapsAsIOException() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "boom.bin", 2, 6);
        // Chunk 0 registers the MergeTask without completing the merge, leaving a window to corrupt its completion future before the isLastChunk branch fires.
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);

        CompletableFuture<Upload> future = (CompletableFuture<Upload>)
                ReflectionTestUtils.invokeMethod(mergeTasksField().get(uploadId), "getMergeCompletionFuture");
        future.completeExceptionally(new RuntimeException("simulated merge failure"));

        IOException thrown = assertThrows(IOException.class, () ->
                asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, true));
        assertEquals("Merge task failed", thrown.getMessage());
        assertInstanceOf(java.util.concurrent.ExecutionException.class, thrown.getCause());
    }

    /**
     * Regression test for a production incident: fileStoragePath defaults to the relative
     * string "files", and MultipartFile.transferTo(File) only does a direct file copy when
     * the destination is absolute -- for a relative File it delegates to the Servlet API's
     * Part.write(String), which resolves relative paths against the *container's* temp/work
     * directory (e.g. Tomcat's own work/Tomcat/... tree), not the app's working directory.
     * A relative temp directory silently redirected every chunk write into that ephemeral
     * location, where the .upload-chunks subdirectory never existed -- FileNotFoundException
     * on every single chunked upload. This test constructs the service directly (not through
     * the Spring context, which redirects storage to an already-absolute @TempDir and would
     * never reproduce the bug) with a deliberately relative fileStoragePath, matching the
     * real default.
     */
    @Test
    void tempDirResolvesToAnAbsolutePathEvenWhenFileStoragePathIsRelative() {
        ApplicationSettingsService settings = org.mockito.Mockito.mock(ApplicationSettingsService.class);
        String relativePath = "target/regression-test-relative-storage-" + UUID.randomUUID();
        org.mockito.Mockito.when(settings.getFileStoragePath()).thenReturn(relativePath);

        AsyncFileMergeService service = new AsyncFileMergeService(
                settings, null, null, null, null, org.mockito.Mockito.mock(StorageService.class));
        try {
            File tempDir = (File) ReflectionTestUtils.invokeMethod(service, "resolveTempDir");
            assertNotNull(tempDir);
            assertTrue(tempDir.isAbsolute(),
                    "the resolved chunk-staging directory must be absolute, or " +
                            "MultipartFile.transferTo() silently writes chunks into the servlet " +
                            "container's temp directory instead of the configured storage volume: " + tempDir);
        } finally {
            deleteRecursively(new File(relativePath).getAbsoluteFile());
        }
    }

    /**
     * A chunk-staging directory that cannot be created must never take uploads down: staging
     * falls back to java.io.tmpdir (the long-standing pre-existing behaviour) rather than
     * failing every upload. Simulated by pointing fileStoragePath at a path whose "parent"
     * is an existing regular file, so creating a directory beneath it is impossible on any
     * OS -- a portable stand-in for the read-only / out-of-space / wrong-permissions mounts
     * that produced this failure in production.
     */
    @Test
    void resolveTempDirFallsBackToSystemTempWhenTheConfiguredLocationCannotBeCreated() throws Exception {
        File blocker = File.createTempFile("quickdrop-not-a-directory", ".tmp");
        blocker.deleteOnExit();

        ApplicationSettingsService settings = org.mockito.Mockito.mock(ApplicationSettingsService.class);
        org.mockito.Mockito.when(settings.getFileStoragePath())
                .thenReturn(new File(blocker, "storage").getPath());

        AsyncFileMergeService service = new AsyncFileMergeService(
                settings, null, null, null, null, org.mockito.Mockito.mock(StorageService.class));

        File tempDir = (File) ReflectionTestUtils.invokeMethod(service, "resolveTempDir");
        assertNotNull(tempDir);
        assertTrue(tempDir.isAbsolute(), "fallback staging directory must still be absolute: " + tempDir);
        assertTrue(tempDir.isDirectory(), "fallback staging directory must actually exist: " + tempDir);
        assertTrue(tempDir.canWrite(), "fallback staging directory must be writable: " + tempDir);
        assertTrue(tempDir.getPath().startsWith(new File(System.getProperty("java.io.tmpdir")).getAbsolutePath()),
                "expected a java.io.tmpdir-based fallback, got: " + tempDir);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
