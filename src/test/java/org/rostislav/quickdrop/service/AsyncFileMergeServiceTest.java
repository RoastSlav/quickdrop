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

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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
        // FINDING (genuine production bug, not a test-assumption error): temp chunk
        // files are named purely by "{taskKey}_chunk_{chunkNumber}" with no
        // per-attempt uniqueness (AsyncFileMergeService.submitChunk / MergeTask). When
        // a duplicate of a chunk that hasn't been drained yet (i.e. arrived out of
        // order and is still parked in MergeTask.pendingChunks) is submitted, the
        // dedup path in MergeTask.enqueueChunk() deletes "chunkInfo.chunkFile" by
        // path -- which is the SAME on-disk file the still-pending original ChunkInfo
        // will later try to read. That later read throws FileNotFoundException and
        // fails the ENTIRE merge task (the whole upload is lost), instead of the
        // duplicate being harmlessly dropped as the "Fix 2: dedup tracker" comment in
        // the source intends.
        //
        // Repro: totalChunks=3; submit chunk index 1 (parked, not yet drained since
        // nextExpectedChunk=0); submit chunk index 1 AGAIN (duplicate); submit chunk
        // index 0 (this drains both 0 and 1 -- chunk 1's file is now missing); submit
        // chunk index 2 (final). Expected: upload completes with content "AAABBBCCC".
        // Actual: submitChunk on the final chunk throws IOException("Merge task
        // failed") wrapping a FileNotFoundException on "..._chunk_1", and the whole
        // upload is silently discarded.
        String uploadId = UUID.randomUUID().toString();
        UploadRequest request = baseRequest(uploadId, "dup.txt", 3, 9);

        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false);
        asyncFileMergeService.submitChunk(request, chunk("BBB".getBytes(StandardCharsets.UTF_8)), 1, false); // duplicate, should be dropped harmlessly
        asyncFileMergeService.submitChunk(request, chunk("AAA".getBytes(StandardCharsets.UTF_8)), 0, false);
        Upload result = asyncFileMergeService.submitChunk(request, chunk("CCC".getBytes(StandardCharsets.UTF_8)), 2, true);

        assertNotNull(result, "the upload must complete despite the duplicate submission -- see FINDING comment above");
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
}
