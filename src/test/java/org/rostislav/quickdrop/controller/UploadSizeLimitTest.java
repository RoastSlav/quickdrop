package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.service.AsyncFileMergeService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The max-file-size setting used to bind only the browser and Tomcat's per-request part
 * limit. Because uploads are chunked, every individual request sits far below that limit,
 * so nothing stopped a caller that ignored the page and posted chunks directly.
 */
class UploadSizeLimitTest extends ControllerTestSupport {

    private static final long LIMIT = 2L * 1024 * 1024;
    private static final int CHUNK = 1024 * 1024;

    @Test
    @DirtiesContext
    void declaredSizeOverTheLimitIsRefusedAtTheFirstChunk() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setMaxFileSize(LIMIT));

        MockMultipartFile part = new MockMultipartFile("file", "big.bin", "application/octet-stream", new byte[8]);
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "big.bin")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "3")
                        .param("fileSize", String.valueOf(LIMIT + 1))
                        .param("uploadId", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
    }

    /**
     * The interesting case: fileSize is the client's word, so a caller can simply understate
     * it. Only measuring what actually arrives catches that.
     */
    @Test
    @DirtiesContext
    void bytesOverTheLimitFailTheMergeEvenWhenTheDeclaredSizeLies() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setMaxFileSize(LIMIT));

        String uploadId = java.util.UUID.randomUUID().toString();
        byte[] chunk = new byte[CHUNK];

        for (int i = 0; i < 3; i++) { // 3 MB actually sent, against a 2 MB limit
            MockMultipartFile part = new MockMultipartFile("file", "big.bin", "application/octet-stream", chunk);
            mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                            .param("fileName", "big.bin")
                            .param("chunkNumber", String.valueOf(i))
                            .param("totalChunks", "3")
                            .param("fileSize", "1024")  // the lie
                            .param("uploadId", uploadId))
                    .andExpect(status().is2xxSuccessful());
        }

        AsyncFileMergeService.UploadProgress progress = waitForUploadCompletion(uploadId);
        assertEquals("failed", progress.status(), "3 MB must not survive a 2 MB limit");
        assertNotNull(progress.error());
        assertTrue(progress.error().contains("maximum file size"), progress.error());
    }

    @Test
    @DirtiesContext
    void anUploadInsideTheLimitStillCompletes() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setMaxFileSize(LIMIT));

        String uploadId = java.util.UUID.randomUUID().toString();
        MockMultipartFile part = new MockMultipartFile("file", "small.bin", "application/octet-stream", new byte[CHUNK]);
        mockMvc.perform(multipart("/api/file/upload-chunk").file(part).with(csrf())
                        .param("fileName", "small.bin")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .param("fileSize", String.valueOf(CHUNK))
                        .param("uploadId", uploadId))
                .andExpect(status().isAccepted());

        assertEquals("complete", waitForUploadCompletion(uploadId).status());
    }
}
