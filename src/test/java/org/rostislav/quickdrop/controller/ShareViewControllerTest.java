package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NB: {@code RateLimitInterceptor} rate-limits {@code /share/**} (group "share-download",
 * 10 requests / 60s per remote address) and its bean is a Spring singleton shared across the
 * whole cached test context -- see application-test.properties. Test methods here are kept to
 * a modest total number of real requests against {@code /share/**} so the suite doesn't trip
 * its own rate limit.
 */
class ShareViewControllerTest extends ControllerTestSupport {

    @Test
    void viewSharedFile_unknownToken_showsInvalidShareLink() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/share/does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }

    @Test
    void viewSharedFile_expiredToken_showsInvalidShareLink() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, java.time.LocalDate.now().minusDays(1), null);
        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }

    @Test
    void viewSharedFile_validUnencryptedToken_showsFileShareView() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);
        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(view().name("file-share-view"))
                .andExpect(model().attributeExists("file", "downloadLink"));
    }

    @Test
    void viewSharedFile_keyHashToken_withoutKey_needsKeyAuth() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);
        token.shareKeyHash = passwordEncoder.encode("the-share-key");
        shareTokenRepository.save(token);

        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(view().name("file-share-view"))
                .andExpect(model().attribute("needsKeyAuth", true));
    }

    @Test
    void viewSharedFile_keyHashToken_wrongLegacyKeyParam_showsInvalidShareLink() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);
        token.shareKeyHash = passwordEncoder.encode("the-share-key");
        shareTokenRepository.save(token);

        mockMvc.perform(get("/share/" + token.code).param("key", "wrong-key"))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }

    // -- POST /share/{token}/auth ------------------------------------------------

    @Test
    void validateShareKey_correctKey_returns200AndEstablishesSession() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);
        token.shareKeyHash = passwordEncoder.encode("the-share-key");
        shareTokenRepository.save(token);

        mockMvc.perform(post("/share/" + token.code + "/auth").with(csrf()).param("key", "the-share-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void validateShareKey_wrongKey_returns403() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);
        token.shareKeyHash = passwordEncoder.encode("the-share-key");
        shareTokenRepository.save(token);

        mockMvc.perform(post("/share/" + token.code + "/auth").with(csrf()).param("key", "wrong-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validateShareKey_unknownToken_returns404() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/share/does-not-exist/auth").with(csrf()).param("key", "anything"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validateShareKey_withoutCsrf_isForbidden() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/share/some-token/auth").param("key", "x"))
                .andExpect(status().isForbidden());
    }
}
