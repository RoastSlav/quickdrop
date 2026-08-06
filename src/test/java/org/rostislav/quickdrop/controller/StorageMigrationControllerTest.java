package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageMigrationControllerTest extends ControllerTestSupport {

    // -- GET /admin/storage-migration ---------------------------------------

    @Test
    void migrationPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/storage-migration").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-storage-migration"))
                .andExpect(model().attributeExists("state", "currentBackend", "backendConfigured"));
    }

    @Test
    void migrationPage_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/storage-migration"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- POST /admin/storage-migration/start -------------------------------------

    @Test
    void startMigration_sameSourceAndDest_rejectedAndRedirects() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/storage-migration/start").session(session).with(csrf())
                        .param("source", "LOCAL")
                        .param("dest", "LOCAL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/storage-migration"));
    }

    @Test
    void startMigration_unconfiguredBackend_rejectedAndRedirects() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/storage-migration/start").session(session).with(csrf())
                        .param("source", "LOCAL")
                        .param("dest", "S3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/storage-migration"));
    }

    @Test
    void startMigration_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/storage-migration/start").session(session)
                        .param("source", "LOCAL").param("dest", "S3"))
                .andExpect(status().isForbidden());
    }

    // -- GET /admin/api/migration-status -----------------------------------------

    @Test
    void migrationStatus_withAdminSession_returnsJson() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/api/migration-status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.total").exists());
    }

    @Test
    void migrationStatus_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/api/migration-status"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- GET /admin/api/migration-preflight --------------------------------------

    @Test
    void migrationPreflight_withAdminSession_returnsCount() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/api/migration-preflight").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").exists());
    }

    // -- GET /admin/api/test-backend ---------------------------------------------

    @Test
    void testBackend_local_succeeds() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "LOCAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /**
     * Regression guard for a fixed NPE: {@code StorageMigrationController#testBackend} now
     * treats "never configured" (a {@code null} endpoint -- the real default for a backend
     * that has never been saved, per {@code ApplicationSettingsService#initSettings()}) and
     * "configured but blank" as the same case, via
     * {@code endpointToCheck == null || endpointToCheck.isBlank()}. Previously the guard was
     * {@code endpointToCheck != null && endpointToCheck.isBlank()}, which silently skipped
     * validation for a never-configured backend and fell through to
     * {@code S3StorageService.buildClient()} -> {@code AwsBasicCredentials.create(null, null)},
     * throwing an uncaught NPE that surfaced as a 500 instead of the intended 400. Same pattern
     * applied to WEBDAV/SFTP. If this guard regresses to the narrower {@code != null &&} form,
     * this test starts failing with a 500 instead of the expected 400.
     */
    @Test
    void testBackend_s3WithoutEndpointConfigured_returns400() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "S3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void testBackend_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/api/test-backend").param("backend", "LOCAL"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- isSafeEndpoint() SSRF guard, exercised through /admin/api/test-backend --------
    // No real network connection is ever reached in these: a rejected endpoint returns
    // 400 before testBackendConnection() is called. Literal IPs (not hostnames) are used
    // throughout so InetAddress.getByName() resolves them locally with no DNS lookup.

    @Test
    void testBackend_webdavUrlPointingAtLoopback_isRejected() throws Exception {
        MockHttpSession session = adminSession();
        updateSettings(s -> s.setWebDavUrl("https://127.0.0.1/dav"));

        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "WEBDAV"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Invalid or internal endpoint address."));
    }

    @Test
    void testBackend_webdavUrlPointingAtRfc1918PrivateAddress_isRejected() throws Exception {
        MockHttpSession session = adminSession();
        updateSettings(s -> s.setWebDavUrl("https://10.0.0.5/dav"));

        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "WEBDAV"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or internal endpoint address."));
    }

    @Test
    void testBackend_webdavUrlPointingAtLinkLocalAddress_isRejected() throws Exception {
        MockHttpSession session = adminSession();
        updateSettings(s -> s.setWebDavUrl("https://169.254.1.1/dav"));

        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "WEBDAV"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or internal endpoint address."));
    }

    @Test
    void testBackend_webdavUrlUsingPlainHttp_isRejected() throws Exception {
        // isSafeEndpoint() requires https:// for URL-form endpoints regardless of host safety.
        MockHttpSession session = adminSession();
        updateSettings(s -> s.setWebDavUrl("http://8.8.8.8/dav"));

        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "WEBDAV"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or internal endpoint address."));
    }

    @Test
    void testBackend_webdavUrlTargetingLocalhostLiteral_isRejected() throws Exception {
        // "localhost" is rejected outright, before any DNS/loopback-address resolution.
        MockHttpSession session = adminSession();
        updateSettings(s -> s.setWebDavUrl("https://localhost/dav"));

        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "WEBDAV"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or internal endpoint address."));
    }

    @Test
    void testBackend_sftpHostAsBareLoopbackHostname_isRejected() throws Exception {
        // SFTP's host field is a bare hostname, not a URL -- isSafeEndpoint() resolves it
        // directly rather than parsing it as a URL first.
        MockHttpSession session = adminSession();
        updateSettings(s -> s.setSftpHost("127.0.0.1"));

        mockMvc.perform(get("/admin/api/test-backend").session(session).param("backend", "SFTP"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid or internal endpoint address."));
    }
}
