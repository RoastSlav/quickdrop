package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.service.AsyncFileMergeService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
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

    // -- GET /api/file/{uuid}/qr.svg --------------------------------------------

    @Test
    void pageQr_publicFile_returnsSvg() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        String body = mockMvc.perform(get("/api/file/" + file.uuid + "/qr.svg"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("<svg"), body.substring(0, Math.min(80, body.length())));
    }

    /**
     * A gated file's QR would encode a page the caller can't open, and answering at all would
     * make this an existence oracle for uuids the interceptor otherwise hides.
     */
    @Test
    void pageQr_passwordProtectedFile_returns404() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes(), "filepw");
        mockMvc.perform(get("/api/file/" + file.uuid + "/qr.svg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pageQr_unknownUuid_returns404() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/api/file/does-not-exist/qr.svg"))
                .andExpect(status().isNotFound());
    }

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

    /**
     * uploadId lands in a filesystem path, so a traversal value must be refused at the
     * edge with a 400 rather than reaching the service (which would answer 500) or, before
     * this was fixed, writing attacker bytes outside the chunk staging directory.
     */
    @Test
    void uploadChunk_traversalUploadId_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "x", "application/octet-stream", "pwn".getBytes());

        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "harmless.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("uploadId", "../../../../etc/cron.d/pwn"))
                .andExpect(status().isBadRequest());
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

    @Test
    @DirtiesContext
    void uploadChunk_adminOnlyUploads_blocksNonAdminSession() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadAdminOnly(true));
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Uploads are restricted to administrators."));
    }

    @Test
    @DirtiesContext
    void uploadChunk_adminOnlyUploads_adminSessionStillAllowed() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadAdminOnly(true));
        MockHttpSession session = adminSession();
        MockMultipartFile part = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        String uploadId = java.util.UUID.randomUUID().toString();
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf()).session(session)
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("fileSize", "1")
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted());
    }

    /**
     * Regression for GHSA-q9xf-5rwh-9hmm (stored XSS via archiveManifest): a manifest entry
     * name containing raw HTML must come back Unicode-escaped ({@code <}/{@code >}/
     * {@code &}) so it cannot execute if ever interpolated into a template or script context.
     */
    @Test
    void uploadChunk_archiveManifestXss_isSanitized() throws Exception {
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
                        .param("archiveUpload", "true")
                        .param("archiveName", "myFolder")
                        .param("archiveManifest", maliciousManifest)
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted());

        AsyncFileMergeService.UploadProgress progress = waitForUploadCompletion(uploadId);
        assertEquals("complete", progress.status());
        StoredFile saved = fileRepository.findByUUID(progress.uuid()).orElseThrow();
        assertNotNull(saved.archiveManifest);
        assertFalse(saved.archiveManifest.contains("<img"), "raw <img tag must not survive sanitizeArchiveManifest()");
        assertFalse(saved.archiveManifest.contains("<script>"), "raw <script> tag must not survive sanitizeArchiveManifest()");
        assertTrue(saved.archiveManifest.contains("\\u003c"), "expected Unicode-escaped '<' in sanitized manifest");
    }

    /**
     * A multi-file selection reuses the folder fields: the same endpoint, but a flat manifest
     * with no {@code dir} entries and a generated archive name instead of a picked directory.
     */
    @Test
    void uploadChunk_looseFileBundle_persistsFlatManifest() throws Exception {
        ensureAdminPasswordSet();
        String manifest = "[{\"path\":\"report.txt\",\"size\":1024,\"type\":\"file\"},"
                + "{\"path\":\"notes.md\",\"size\":2048,\"type\":\"file\"}]";
        MockMultipartFile part = new MockMultipartFile("file", "files.zip", "application/zip", "zipbytes".getBytes());
        String uploadId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "files.zip")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("fileSize", "8")
                        .param("archiveUpload", "true")
                        .param("archiveName", "files")
                        .param("archiveManifest", manifest)
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted());

        AsyncFileMergeService.UploadProgress progress = waitForUploadCompletion(uploadId);
        assertEquals("complete", progress.status());
        StoredFile saved = fileRepository.findByUUID(progress.uuid()).orElseThrow();
        assertTrue(saved.archiveUpload, "a loose-file bundle is stored as an archive upload");
        assertEquals("files", saved.archiveName);
        assertTrue(saved.name.startsWith("files-"), "a bundle name gets its code appended: " + saved.name);
        assertNotNull(saved.archiveManifest);
        assertTrue(saved.archiveManifest.contains("report.txt"));
        assertFalse(saved.archiveManifest.contains("\"dir\""), "a flat bundle has no directory entries");
    }

    /**
     * The browser cannot see which names are taken, so it names a bundle for its date alone
     * and the distinguishing code is appended here. Two bundles uploaded the same day must
     * therefore not end up sharing a name.
     */
    @Test
    void uploadChunk_looseFileBundle_getsADistinctNameAppendedServerSide() throws Exception {
        ensureAdminPasswordSet();
        String manifest = "[{\"path\":\"a.txt\",\"size\":1,\"type\":\"file\"},"
                + "{\"path\":\"b.txt\",\"size\":2,\"type\":\"file\"}]";

        String first = uploadArchive("files-2692.zip", manifest);
        String second = uploadArchive("files-2692.zip", manifest);

        assertTrue(first.startsWith("files-2692-"), first);
        assertEquals("files-2692-".length() + 4 + ".zip".length(), first.length(), first);
        assertNotEquals(first, second, "two bundles from the same day must not share a name");
    }

    /**
     * A picked folder is named after the folder itself, which the uploader chose -- only a
     * generated bundle name gets a code appended.
     */
    @Test
    void uploadChunk_folderUpload_keepsItsFolderName() throws Exception {
        ensureAdminPasswordSet();
        String manifest = "[{\"path\":\"docs\",\"type\":\"dir\"},"
                + "{\"path\":\"docs/a.txt\",\"size\":1,\"type\":\"file\"}]";

        assertEquals("docs.zip", uploadArchive("docs.zip", manifest));
    }

    private String uploadArchive(String fileName, String manifest) throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", fileName, "application/zip", "zipbytes".getBytes());
        String uploadId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", fileName)
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("fileSize", "8")
                        .param("archiveUpload", "true")
                        .param("archiveName", "files")
                        .param("archiveManifest", manifest)
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted());

        AsyncFileMergeService.UploadProgress progress = waitForUploadCompletion(uploadId);
        assertEquals("complete", progress.status());
        return fileRepository.findByUUID(progress.uuid()).orElseThrow().name;
    }

    /**
     * The manifest is client-supplied text that gets parsed and then stored verbatim, so an
     * oversized one has to be refused at the edge rather than reaching the database.
     */
    @Test
    void uploadChunk_oversizedArchiveManifest_returns400() throws Exception {
        ensureAdminPasswordSet();
        StringBuilder manifest = new StringBuilder("[");
        while (manifest.length() < 1024 * 1024) {
            manifest.append("{\"path\":\"").append("p".repeat(200)).append("\",\"type\":\"file\"},");
        }
        manifest.append("{\"path\":\"last.txt\",\"type\":\"file\"}]");

        MockMultipartFile part = new MockMultipartFile("file", "files.zip", "application/zip", "zipbytes".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "files.zip")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("archiveUpload", "true")
                        .param("archiveManifest", manifest.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadChunk_malformedArchiveManifest_returns400() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile part = new MockMultipartFile("file", "folder.zip", "application/zip", "zipbytes".getBytes());
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "folder.zip")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("archiveUpload", "true")
                        .param("archiveManifest", "not-json-at-all"))
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
        mockMvc.perform(get("/api/file/download/" + token.code))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/share/" + token.code));
    }

    @Test
    void downloadByToken_unlimitedValidToken_streamsFile() throws Throwable {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello world".getBytes());
        var token = createShareToken(file, null, null);

        // StreamingResponseBody triggers Servlet async processing; the body is only
        // available after the async dispatch completes.
        retryOnMockMvcAsyncHeaderRace(() -> {
            MvcResult result = mockMvc.perform(get("/api/file/download/" + token.code))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                    .andExpect(content().bytes("hello world".getBytes()));
        });
    }

    @Test
    void downloadByToken_exhaustedDownloads_isRemovedAndRedirectsOnNextAttempt() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        var token = createShareToken(file, null, 1);

        MvcResult first = mockMvc.perform(get("/api/file/download/" + token.code))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk());

        // Second attempt: counter is now 0 -> token invalid -> redirect.
        mockMvc.perform(get("/api/file/download/" + token.code))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/share/" + token.code));
    }
}
