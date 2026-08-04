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
     * FINDING (genuine production bug, not a test bug -- kept failing intentionally per the
     * task instructions rather than weakened): {@code StorageMigrationController#testBackend}
     * guards the "no host/URL configured" case with
     * {@code if (endpointToCheck != null && endpointToCheck.isBlank())}. When S3 has never been
     * configured, {@code ApplicationSettingsService#getS3Endpoint()} returns {@code null} (the
     * real default -- see ApplicationSettingsService#initSettings(), which never seeds
     * s3Endpoint), so {@code endpointToCheck != null} is false and this guard is silently
     * skipped entirely (it only catches "configured but blank", not "never configured"). The
     * same is true for the {@code !isSafeEndpoint(...)} guard immediately below it. Execution
     * falls through to {@code applicationSettingsService.testBackendConnection(S3)} ->
     * {@code S3StorageService.buildClient()}, which calls
     * {@code AwsBasicCredentials.create(null, null)} and throws an uncaught
     * {@code NullPointerException("Access key ID cannot be blank.")}, surfacing as an unhandled
     * 500 instead of the intended graceful 400 "No host or URL configured for this backend."
     * The same bug pattern applies to WEBDAV/SFTP, whose blank-check has the same
     * {@code != null &&} short-circuit.
     * <p>
     * Repro: fresh install (S3 never configured), admin session,
     * {@code GET /admin/api/test-backend?backend=S3} -> 500 instead of 400.
     * <p>
     * Expected fix (not applied here per the "don't edit src/main/java" rule): change both
     * guards to {@code endpointToCheck == null || endpointToCheck.isBlank()}.
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
}
