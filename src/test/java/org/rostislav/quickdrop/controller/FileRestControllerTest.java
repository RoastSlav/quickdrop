package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.service.AsyncFileMergeService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileRestControllerTest extends ControllerTestSupport {

    // -- POST /api/file/upload-chunk --------------------------------------------

    @Test
    void uploadChunk_singleChunkFile_returns202WithUploadId() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "hello.txt", "text/plain", "hello world".getBytes());
        String uploadId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "hello.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("fileSize", "11")
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.uploadId").value(uploadId));

        AsyncFileMergeService.UploadProgress progress = waitForUploadCompletion(uploadId);
        assertEquals("complete", progress.status());
        assertNotNull(progress.uuid());
        Optional<Upload> saved = fileRepository.findByUUID(progress.uuid()).map(f -> (Upload) f);
        assertTrue(saved.isPresent());
        assertEquals("hello.txt", saved.get().name);
    }

    @Test
    void uploadChunk_intermediateChunk_returns200WithNullBody() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "big.bin", "application/octet-stream", new byte[]{1, 2, 3});
        String uploadId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "big.bin")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "2")
                        .param("uploadId", uploadId))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        // Clean up the still-pending upload so it doesn't linger against the shared executor.
        asyncFileMergeService.abortUpload(uploadId);
    }

    @Test
    void uploadChunk_emptyFile_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/api/file/upload-chunk").file(empty).with(csrf())
                        .param("fileName", "empty.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Empty files cannot be uploaded."));
    }

    @Test
    void uploadChunk_withoutCsrf_isForbidden() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part)
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext
    void uploadChunk_uploadsDisabled_returns403() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadEnabled(false));
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("File uploads are currently disabled."));
    }

    /**
     * Regression for GHSA-q9xf-5rwh-9hmm (stored XSS via folderManifest): a manifest entry
     * name containing raw HTML must come back Unicode-escaped ({@code <}/{@code >}/
     * {@code &}) so it cannot execute if ever interpolated into a template or script context.
     */
    @Test
    void uploadChunk_folderManifestXss_isSanitized() throws Exception {
        ensureAdminPasswordSet();
        String maliciousManifest = "[{\"name\": \"<img src=x onerror=alert(1)>.txt\", \"path\": \"a.txt\"}, "
                + "{\"name\": \"\\\"><script>alert(2)</script>\", \"path\": \"b.txt\"}]";
        MockMultipartFile part = new MockMultipartFile("file", "folder.zip", "application/zip", "zipbytes".getBytes());
        String uploadId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "folder.zip")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("fileSize", "8")
                        .param("folderUpload", "true")
                        .param("folderName", "myFolder")
                        .param("folderManifest", maliciousManifest)
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted());

        AsyncFileMergeService.UploadProgress progress = waitForUploadCompletion(uploadId);
        assertEquals("complete", progress.status());
        StoredFile saved = fileRepository.findByUUID(progress.uuid()).orElseThrow();
        assertNotNull(saved.folderManifest);
        assertFalse(saved.folderManifest.contains("<img"), "raw <img tag must not survive sanitizeFolderManifest()");
        assertFalse(saved.folderManifest.contains("<script>"), "raw <script> tag must not survive sanitizeFolderManifest()");
        assertTrue(saved.folderManifest.contains("\\u003c"), "expected Unicode-escaped '<' in sanitized manifest");
    }

    @Test
    void uploadChunk_malformedFolderManifest_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "folder.zip", "application/zip", "zipbytes".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "folder.zip")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("folderUpload", "true")
                        .param("folderManifest", "not-json-at-all"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadChunk_blankFileName_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadChunk_zeroTotalChunks_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadChunk_chunkNumberOutOfRange_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "5")
                        .param("totalChunks", "3"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Regression for docs/test-reports/endpoint-matrix.md SEV-3-01: submitting a chunk after
     * the upload was aborted must return a graceful 4xx, not a generic 500 -- the deliberate
     * "aborted" IOException was previously indistinguishable from a genuine I/O failure.
     */
    @Test
    void uploadChunk_afterAbort_returns409NotFiveHundred() throws Exception {
        ensureAdminPasswordSet();
        String uploadId = java.util.UUID.randomUUID().toString();
        MockMultipartFile chunk0 = new MockMultipartFile("file", "big.bin", "application/octet-stream", "chunk0".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(chunk0).with(csrf())
                        .param("fileName", "big.bin")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "3")
                        .param("uploadId", uploadId))
                .andExpect(status().isOk());

        asyncFileMergeService.abortUpload(uploadId);

        MockMultipartFile chunk1 = new MockMultipartFile("file", "big.bin", "application/octet-stream", "chunk1".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(chunk1).with(csrf())
                        .param("fileName", "big.bin")
                        .param("chunkNumber", "1")
                        .param("totalChunks", "3")
                        .param("uploadId", uploadId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This upload was aborted."));
    }

    // -- POST /api/file/upload-abort ---------------------------------------------

    @Test
    void uploadAbort_missingUploadId_returns400() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/file/upload-abort").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadAbort_validId_returns204() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/file/upload-abort").with(csrf()).param("uploadId", "never-started"))
                .andExpect(status().isNoContent());
    }

    // -- GET /api/file/upload-status/{uploadId} ----------------------------------

    @Test
    void uploadStatus_unknownId_returns200WithUnknownStatus() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/api/file/upload-status/does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unknown"));
    }

    // -- POST /api/file/share/{uuid} ---------------------------------------------

    @Test
    void generateShareLink_forPlainFile_returns200WithToken() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        mockMvc.perform(post("/api/file/share/" + file.uuid).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.sharePath").exists())
                .andExpect(jsonPath("$.preparingMessage").value("false"));
    }

    @Test
    void generateShareLink_unknownFile_returns404() throws Exception {
        // FilePasswordInterceptor also covers /api/file/share/{uuid}: it looks up the uuid
        // itself and returns 404 for an unknown file before FileRestController's own "file not
        // found -> 400" branch is ever reached, making that controller branch effectively dead
        // for this specific endpoint.
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/file/share/does-not-exist").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void generateShareLink_negativeDownloads_returns400() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        mockMvc.perform(post("/api/file/share/" + file.uuid).with(csrf()).param("nOfDownloads", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DirtiesContext
    void generateShareLink_disabled_returns403() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShareLinksEnabled(false));
        StoredFile file = createFile("a.txt", "hello".getBytes());
        mockMvc.perform(post("/api/file/share/" + file.uuid).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void generateShareLink_passwordProtectedFile_withoutFileSession_isBlockedByInterceptor() throws Exception {
        // FilePasswordInterceptor covers /api/file/share/** too; a password-protected file
        // without a valid file-session token never reaches the controller's own 403 branch --
        // it is redirected to /file/password/{uuid} first.
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hello".getBytes(), "filepw");
        mockMvc.perform(post("/api/file/share/" + file.uuid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    // -- GET /api/file/download/{token} ------------------------------------------

    @Test
    void downloadByToken_unknownToken_redirectsToSharePage() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/api/file/download/does-not-exist"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/share/does-not-exist"));
    }

    @Test
    void downloadByToken_expiredToken_redirectsToSharePage() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        var token = createShareToken(file, LocalDate.now().minusDays(1), null);
        mockMvc.perform(get("/api/file/download/" + token.shareToken))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/share/" + token.shareToken));
    }

    @Test
    void downloadByToken_unlimitedValidToken_streamsFile() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello world".getBytes());
        var token = createShareToken(file, null, null);

        // StreamingResponseBody triggers Servlet async processing; the body is only
        // available after the async dispatch completes.
        MvcResult result = mockMvc.perform(get("/api/file/download/" + token.shareToken))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes("hello world".getBytes()));
    }

    @Test
    void downloadByToken_exhaustedDownloads_isRemovedAndRedirectsOnNextAttempt() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        var token = createShareToken(file, null, 1);

        MvcResult first = mockMvc.perform(get("/api/file/download/" + token.shareToken))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk());

        // Second attempt: counter is now 0 -> token invalid -> redirect.
        mockMvc.perform(get("/api/file/download/" + token.shareToken))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/share/" + token.shareToken));
    }
}
