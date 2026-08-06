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

    // -------------------------------------------------------------------------
    // Full round trip across the real 4 MB chunk boundary
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Out-of-order chunk submission
    // -------------------------------------------------------------------------

    @Test
    @Timeout(30)
    void outOfOrderChunkSubmissionStillAssemblesInCorrectSequence() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "ordered.txt", 3, 9);

        // Submit index 1 before index 0; index 2 (the actual last chunk) submitted last.
        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false);
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);
        Upload result = asyncFileMergeService.submitChunk(request, chunk("CCC".getBytes(StandardCharsets.UTF_8)), 2, true);

        assertNotNull(result);
        byte[] storedBytes = readStoredBytes(result.uuid);
        assertEquals("AAABBBCCC", new String(storedBytes, StandardCharsets.UTF_8),
                "chunks must be written in index order regardless of arrival order");
    }

    // -------------------------------------------------------------------------
    // Duplicate chunk index
    // -------------------------------------------------------------------------

    @Test
    @Timeout(30)
    void duplicateChunkSubmissionIsDroppedNotDuplicated() throws Exception {
        // Regression guard for a fixed bug: temp chunk files are now named
        // "{taskKey}_chunk_{chunkNumber}_{UUID}" (per-attempt-unique), not just
        // "{taskKey}_chunk_{chunkNumber}". Previously, a duplicate submission of a chunk
        // that hadn't been drained yet (still parked in MergeTask.pendingChunks) would have
        // its dedup path in MergeTask.enqueueChunk() delete "chunkInfo.chunkFile" by path --
        // the SAME on-disk file the still-pending original ChunkInfo would later try to read,
        // throwing FileNotFoundException and failing the entire merge task (the whole upload
        // lost), instead of the duplicate being harmlessly dropped. The per-attempt UUID
        // means the duplicate's file is now distinct from the pending original's, so deleting
        // it can't affect the original's read.
        //
        // Scenario: totalChunks=3; submit chunk index 1 (parked, not yet drained since
        // nextExpectedChunk=0); submit chunk index 1 AGAIN (duplicate); submit chunk
        // index 0 (this drains both 0 and 1); submit chunk index 2 (final). Expected (and
        // now actual): upload completes with content "AAABBBCCC". If the per-attempt
        // uniqueness regresses, this starts throwing IOException("Merge task failed") again.
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "dup.txt", 3, 9);

        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false);
        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false); // duplicate, should be dropped harmlessly
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);
        Upload result = asyncFileMergeService.submitChunk(request, chunk("CCC".getBytes(StandardCharsets.UTF_8)), 2, true);

        assertNotNull(result, "the upload must complete despite the duplicate submission -- see comment above");
        byte[] storedBytes = readStoredBytes(result.uuid);
        assertEquals("AAABBBCCC", new String(storedBytes, StandardCharsets.UTF_8),
                "a duplicate chunk submission must not be written twice, and must not destroy the still-pending original");
    }

    // -------------------------------------------------------------------------
    // abortUpload
    // -------------------------------------------------------------------------

    @Test
    @Timeout(30)
    void abortUploadCleansUpTempChunkFilesFromDisk() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "aborted.bin", 3, 30);

        // Submit chunk index 1 (not the first-expected chunk) so it stays parked,
        // un-drained, in the merge task's temp directory rather than being immediately
        // written and deleted.
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

    // -------------------------------------------------------------------------
    // getUploadStatus
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // totalChunks DoS guard (Fix 3 in the source)
    // -------------------------------------------------------------------------

    @Test
    void submitChunkRejectsAbsurdTotalChunks() {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "huge.bin", Integer.MAX_VALUE, 1);

        assertThrows(IllegalArgumentException.class, () ->
                asyncFileMergeService.submitChunk(request, chunk("x".getBytes(StandardCharsets.UTF_8)), 0, false));
    }

    // -------------------------------------------------------------------------
    // evictStaleTasks() -- the TTL sweeper, private and normally scheduled every
    // TASK_TTL_MINUTES (60) minutes. Reflection is used both to invoke it directly and to
    // backdate a task's lastActivityAt, since waiting 60 real minutes isn't practical.
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeTasksField() {
        return (Map<String, Object>) ReflectionTestUtils.getField(asyncFileMergeService, "mergeTasks");
    }

    @Test
    @Timeout(30)
    void evictStaleTasks_removesTaskInactiveBeyondTtl() throws Exception {
        // Chunk file cleanup itself is already covered by abortUploadCleansUpTempChunkFilesFromDisk
        // (evictStaleTasks delegates to the same cleanup as an explicit abort) -- asserting it
        // again here would race against the real background MergeTask thread, which may still
        // hold the chunk file open under Windows file-locking semantics at the instant eviction
        // runs. This test focuses on what's specific to the TTL sweep itself: that a task past
        // the TTL is actually removed and marked aborted.
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "stale.bin", 3, 30);
        // Chunk 1 (not the first-expected) stays parked, un-drained, on disk.
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
        // lastActivityAt left at its real "just now" value -- well within the TTL.

        try {
            ReflectionTestUtils.invokeMethod(asyncFileMergeService, "evictStaleTasks");

            assertTrue(mergeTasksField().containsKey(uploadId), "a recently active task must not be evicted");
        } finally {
            // Chunk 0 was deliberately never sent, so -- proving this task is deliberately left
            // running is the point of the test -- its background MergeTask would otherwise leak
            // for the life of the JVM, parked waiting for a chunk that will never arrive.
            asyncFileMergeService.abortUpload(uploadId);
        }
    }

    // -------------------------------------------------------------------------
    // submitChunk's waitForCompletion=true branch: InterruptedException / ExecutionException
    // -------------------------------------------------------------------------

    @Test
    @Timeout(30)
    void submitChunk_waitForCompletionInterrupted_wrapsAsIOException() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        // totalChunks=2, and chunk 0 (the first-expected chunk) is deliberately never sent --
        // the drain loop can then never complete the merge, so the completion future the
        // background thread blocks on stays genuinely pending regardless of scheduling, making
        // this deterministic rather than a timing race.
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
        // No fixed sleep needed for correctness (the future can't complete on its own -- see
        // above); a short wait just gives the thread a realistic chance to reach the blocking
        // get() before interrupt() is delivered.
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
            // Interrupting the waiter only unblocks submitChunk's own get() call -- chunk 0 was
            // deliberately never sent, so the real background MergeTask is still alive, parked
            // waiting for it. Without this, it would leak for the life of the JVM.
            asyncFileMergeService.abortUpload(uploadId);
        }
    }

    @Test
    @Timeout(30)
    void submitChunk_waitForCompletionOnExceptionallyCompletedFuture_wrapsAsIOException() throws Exception {
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "boom.bin", 2, 6);
        // Chunk 0 registers the MergeTask without completing the merge (chunk 1 -- the last
        // one -- hasn't arrived yet), leaving a real window to corrupt its completion future
        // before ever triggering the isLastChunk branch.
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);

        CompletableFuture<Upload> future = (CompletableFuture<Upload>)
                ReflectionTestUtils.invokeMethod(mergeTasksField().get(uploadId), "getMergeCompletionFuture");
        future.completeExceptionally(new RuntimeException("simulated merge failure"));

        IOException thrown = assertThrows(IOException.class, () ->
                asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, true));
        assertEquals("Merge task failed", thrown.getMessage());
        assertInstanceOf(java.util.concurrent.ExecutionException.class, thrown.getCause());
    }
}
