package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShortLinkRestControllerTest extends ControllerTestSupport {

    @Test
    void createLink_validUrl_returns200WithCodeAndShortUrl() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").with(csrf()).param("url", "example.com/a/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.shortUrl").value(org.hamcrest.Matchers.containsString("/s/")))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/a/page"));
    }

    @Test
    void createLink_unsafeDestination_returns400() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").with(csrf()).param("url", "http://127.0.0.1/admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void createLink_negativeMaxUses_returns400() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").with(csrf())
                        .param("url", "example.com")
                        .param("maxUses", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLink_withoutCsrf_isForbidden() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").param("url", "example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createLink_reservedAlias_returns400() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").with(csrf())
                        .param("url", "example.com")
                        .param("customAlias", "admin"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DirtiesContext
    void createLink_shortenerDisabled_returns403() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerEnabled(false));
        mockMvc.perform(post("/api/link").with(csrf()).param("url", "example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext
    void createLink_adminOnly_blocksNonAdminSession() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerAdminOnly(true));
        mockMvc.perform(post("/api/link").with(csrf()).param("url", "example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DirtiesContext
    void createLink_adminOnly_adminSessionStillAllowed() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerAdminOnly(true));
        mockMvc.perform(post("/api/link").with(csrf()).session(adminSession()).param("url", "example.com"))
                .andExpect(status().isOk());
    }

    @Test
    @DirtiesContext
    void createLink_customAliasDisabled_rejectsAlias() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerCustomAliasEnabled(false));
        mockMvc.perform(post("/api/link").with(csrf())
                        .param("url", "example.com")
                        .param("customAlias", "my-alias"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLink_customAliasAdminOnlyByDefault_blocksNonAdmin() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").with(csrf())
                        .param("url", "example.com")
                        .param("customAlias", "my-alias"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLink_customAliasAdminOnlyByDefault_allowsAdminSession() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/api/link").with(csrf()).session(adminSession())
                        .param("url", "example.com")
                        .param("customAlias", "my-alias-" + java.util.UUID.randomUUID().toString().substring(0, 8)))
                .andExpect(status().isOk());
    }

    @Test
    @DirtiesContext
    void createLink_domainBlocklist_rejectsListedDomain() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> {
            s.setShortenerDomainRuleMode("BLOCKLIST");
            s.setShortenerDomainRules("evil.com");
        });
        mockMvc.perform(post("/api/link").with(csrf()).param("url", "https://evil.com/page"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qrSvg_validUploadShareLink_returns200Svg() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);

        mockMvc.perform(get("/api/link/" + token.code + "/qr.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("<svg")));
    }

    @Test
    void qrPng_validUploadShareLink_returns200Png() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);

        mockMvc.perform(get("/api/link/" + token.code + "/qr.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
    }

    @Test
    void qrSvg_unknownCode_returns404() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/api/link/does-not-exist/qr.svg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrSvg_expiredLink_returns404() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, LocalDate.now().minusDays(1), null);

        mockMvc.perform(get("/api/link/" + token.code + "/qr.svg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrSvg_exhaustedLink_returns404() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, 0);

        mockMvc.perform(get("/api/link/" + token.code + "/qr.svg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void qrSvg_respectsSizeParameter() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);

        mockMvc.perform(get("/api/link/" + token.code + "/qr.svg").param("size", "512"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("width=\"512\"")));
    }
}
