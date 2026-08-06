package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminViewControllerTest extends ControllerTestSupport {

    // -- GET /admin/dashboard -------------------------------------------------

    @Test
    void dashboard_withAdminSession_returns200WithAnalytics() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/dashboard").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("analytics"));
    }

    @Test
    void dashboard_unauthenticated_redirectsToAdminPassword() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- GET /admin/files -------------------------------------------------------

    @Test
    void filesPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        createFile("a.txt", "hello".getBytes());
        mockMvc.perform(get("/admin/files").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-files"))
                .andExpect(model().attributeExists("filesPage"));
    }

    @Test
    void filesPage_withQueryAndDeletedFlag_returns200() throws Exception {
        MockHttpSession session = adminSession();
        createDeletedFile("gone.txt", "bye".getBytes());
        mockMvc.perform(get("/admin/files").session(session).param("query", "gone").param("deleted", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("showDeleted", true));
    }

    // -- GET /admin/pastes --------------------------------------------------

    @Test
    void pastesPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        createPaste("note", "content", null, false, false);
        mockMvc.perform(get("/admin/pastes").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-pastes"))
                .andExpect(model().attributeExists("pastesPage"));
    }

    // -- GET /admin/pastes/{uuid}/history -------------------------------------

    @Test
    void pasteHistory_forPaste_returns200() throws Exception {
        MockHttpSession session = adminSession();
        Paste paste = createPaste("note", "content", null, false, false);
        mockMvc.perform(get("/admin/pastes/" + paste.uuid + "/history").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-paste-history"))
                .andExpect(model().attributeExists("paste", "actionLogs"));
    }

    @Test
    void pasteHistory_forNonPasteUuid_redirectsToPastes() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(get("/admin/pastes/" + file.uuid + "/history").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/pastes"));
    }

    // -- GET/POST /admin/setup ------------------------------------------------

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void setupPage_whenNoAdminPassword_showsWelcome() throws Exception {
        // Needs a pristine "no admin password yet" database. Class-level @DirtiesContext isn't
        // enough here: JUnit doesn't run test methods in declaration order, so another method in
        // this very class could set the admin password before this one runs. Method-level
        // BEFORE_METHOD guarantees a fresh context regardless of sibling ordering.
        mockMvc.perform(get("/admin/setup"))
                .andExpect(status().isOk())
                .andExpect(view().name("welcome"));
    }

    @Test
    void setupPage_whenAdminPasswordSet_redirectsToDashboard() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/setup"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("dashboard"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void postSetup_setsAdminPasswordAndRedirects() throws Exception {
        // Needs a pristine "no admin password yet" database -- see setupPage_whenNoAdminPassword
        // above. Sets the SAME password as ensureAdminPasswordSet()/adminSession() elsewhere in
        // this suite (rather than an arbitrary literal) so this test doesn't leave behind a
        // hash other tests' ADMIN_PASSWORD-based assertions would no longer match.
        assertFalse(applicationSettingsService.isAdminPasswordSet());
        mockMvc.perform(post("/admin/setup").with(csrf()).param("adminPassword", ADMIN_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("dashboard"));
        assertTrue(applicationSettingsService.isAdminPasswordSet());
    }

    @Test
    void postSetup_whenAlreadySet_refusesOverwrite() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/setup").with(csrf()).param("adminPassword", "someone-elses-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));
        // Original password still works; hash wasn't overwritten.
        assertTrue(org.springframework.security.crypto.bcrypt.BCrypt.checkpw(ADMIN_PASSWORD, applicationSettingsService.getAdminPasswordHash()));
    }

    @Test
    void postSetup_withoutCsrf_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/setup").param("adminPassword", "brand-new-pw-123"))
                .andExpect(status().isForbidden());
    }

    // -- GET /admin/settings ---------------------------------------------------

    @Test
    void settingsPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/settings").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(model().attributeExists("settings", "aboutInfo"));
    }

    // -- POST /admin/save & /admin/api/save -----------------------------------

    // NB: ApplicationSettingsViewModel binds every unset boolean form field to Java's default
    // (false) -- since AdminViewController#saveSettings persists the *entire* view model,
    // omitting a feature flag here would silently disable it for the rest of the suite (this
    // context is cached/shared across test classes with identical config; see
    // application-test.properties). Explicitly echo back the defaults for every flag so this
    // helper models a realistic "admin edits one field, submits the whole form" save rather
    // than "admin turns everything off". @DirtiesContext on the two tests that actually persist
    // (below) is a second line of defense.
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsFormParams(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String cron) {
        return settingsFormParams(builder, cron, "1024", "30", storageDir.toString());
    }

    // Overload for tests that need to vary maxFileSize/maxFileLifeTime/fileStoragePath --
    // MockHttpServletRequestBuilder#param() ACCUMULATES values for a repeated key rather than
    // replacing them, so chaining an extra .param("maxFileSize", "-500") onto the single-arg
    // overload above does NOT override its "1024" (Spring's binder took the first value,
    // silently defeating the intended override) -- build the full param list in one place instead.
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsFormParams(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String cron,
            String maxFileSize, String maxFileLifeTime, String fileStoragePath) {
        return builder
                .param("maxFileSize", maxFileSize)
                .param("maxFileLifeTime", maxFileLifeTime)
                .param("fileStoragePath", fileStoragePath)
                .param("fileDeletionCron", cron)
                .param("backupCron", "0 0 4 * * *")
                .param("maxBackups", "7")
                .param("sessionLifeTime", "30")
                .param("maxPreviewSizeBytes", "5")
                .param("isFileListPageEnabled", "true")
                .param("isAdminDashboardButtonEnabled", "true")
                .param("encryptionEnabled", "true")
                .param("uploadPasswordEnabled", "true")
                .param("previewEnabled", "true")
                .param("shareLinksEnabled", "true")
                .param("uploadEnabled", "true")
                .param("pastebinEnabled", "true");
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext
    void postSave_validSettings_redirectsToSettings() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(settingsFormParams(post("/admin/save").session(session).with(csrf()), "0 0 2 * * *"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("settings"));
    }

    @Test
    void postSave_invalidCron_redirectsWithError() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(settingsFormParams(post("/admin/save").session(session).with(csrf()), "not a cron"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("settings?error=invalidCron"));
    }

    @Test
    void postSave_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(settingsFormParams(post("/admin/save").session(session), "0 0 2 * * *"))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext
    void postApiSave_validSettings_returns200() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "0 0 2 * * *"))
                .andExpect(status().isOk())
                .andExpect(content().string("Settings saved"));
    }

    @Test
    void postApiSave_invalidCron_returns400() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "garbage"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Settings validation regression (docs/test-reports/security-probes.md SEV-2):
     * out-of-range numeric settings and storage paths outside the app directory were
     * previously accepted and persisted with no bounds checking whatsoever.
     */
    @Test
    void postApiSave_negativeMaxFileSize_returns400AndDoesNotPersist() throws Exception {
        MockHttpSession session = adminSession();
        long before = applicationSettingsService.getMaxFileSize();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "0 0 2 * * *",
                        "-500", "30", storageDir.toString()))
                .andExpect(status().isBadRequest());
        assertEquals(before, applicationSettingsRepository.findById(1L).orElseThrow().getMaxFileSize());
    }

    @Test
    void postApiSave_zeroRetention_returns400AndDoesNotPersist() throws Exception {
        MockHttpSession session = adminSession();
        long before = applicationSettingsService.getMaxFileLifeTime();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "0 0 2 * * *",
                        "1024", "0", storageDir.toString()))
                .andExpect(status().isBadRequest());
        assertEquals(before, applicationSettingsRepository.findById(1L).orElseThrow().getMaxFileLifeTime());
    }

    @Test
    void postApiSave_dangerousStoragePath_returns400AndDoesNotPersist() throws Exception {
        MockHttpSession session = adminSession();
        String before = applicationSettingsService.getFileStoragePath();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "0 0 2 * * *",
                        "1024", "30", "C:\\Windows"))
                .andExpect(status().isBadRequest());
        assertEquals(before, applicationSettingsRepository.findById(1L).orElseThrow().getFileStoragePath());
    }

    @Test
    void postApiSave_traversalStoragePath_returns400AndDoesNotPersist() throws Exception {
        MockHttpSession session = adminSession();
        String before = applicationSettingsService.getFileStoragePath();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "0 0 2 * * *",
                        "1024", "30", "files/../../escaped"))
                .andExpect(status().isBadRequest());
        assertEquals(before, applicationSettingsRepository.findById(1L).orElseThrow().getFileStoragePath());
    }

    @Test
    void postApiSave_absoluteNonDangerousStoragePath_isAccepted() throws Exception {
        // Docker deployments legitimately mount the storage root at an absolute path
        // (README: "mount /app/db, /app/files, /app/log") -- only a short list of
        // well-known OS-critical directories is rejected, not absolute paths in general.
        MockHttpSession session = adminSession();
        mockMvc.perform(settingsFormParams(post("/admin/api/save").session(session).with(csrf()), "0 0 2 * * *",
                        "1024", "30", storageDir.toString()))
                .andExpect(status().isOk());
    }

    // -- POST /admin/password --------------------------------------------------

    @Test
    void postPassword_correctPassword_redirectsToDashboardAndEstablishesSession() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/password").with(csrf()).param("password", ADMIN_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("dashboard"));
    }

    @Test
    void postPassword_wrongPassword_redirectsWithError() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/password").with(csrf()).param("password", "totally-wrong"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("password?error"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void postPassword_noAdminPasswordSetYet_redirectsToSetup() throws Exception {
        // Needs a pristine "no admin password yet" database -- see setupPage_whenNoAdminPassword.
        mockMvc.perform(post("/admin/password").with(csrf()).param("password", "anything"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/setup"));
    }

    // -- GET /admin, /admin/ ----------------------------------------------------

    @Test
    void adminRoot_redirectsToDashboard() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    // -- POST /admin/logout -------------------------------------------------

    @Test
    void logout_redirectsToRootAndInvalidatesSession() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        // Session should no longer grant admin access.
        mockMvc.perform(get("/admin/dashboard").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- GET /admin/password (no auth required) ---------------------------------

    @Test
    void adminPasswordPage_isPubliclyReachable() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/password"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-password"));
    }

    // -- POST /admin/keep-indefinitely/{uuid} ------------------------------------

    @Test
    void keepIndefinitely_withAdminSession_redirectsToFiles() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/keep-indefinitely/" + file.uuid).session(session).with(csrf())
                        .param("keepIndefinitely", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/files"));
    }

    @Test
    void keepIndefinitely_unauthenticated_redirectsToAdminPassword() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/keep-indefinitely/" + file.uuid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- POST /admin/toggle-hidden/{uuid} ----------------------------------------

    @Test
    void toggleHidden_withAdminSession_redirectsToFiles() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/toggle-hidden/" + file.uuid).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/files"));
    }

    // -- POST /admin/delete/{uuid} -----------------------------------------------

    @Test
    void deleteFile_withAdminSession_nonAjax_redirects() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/delete/" + file.uuid).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/files"));
    }

    @Test
    void deleteFile_withAdminSession_ajax_returns204() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/delete/" + file.uuid).session(session).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteFile_unauthenticated_redirectsToAdminPassword_regressionForGHSA_q8mc() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/delete/" + file.uuid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
        // File must still exist -- the delete must not have gone through.
        assertTrue(fileRepository.findByUUID(file.uuid).isPresent());
        assertFalse(fileRepository.findByUUID(file.uuid).get().deleted);
    }

    @Test
    void deleteFile_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(post("/admin/delete/" + file.uuid).session(session))
                .andExpect(status().isForbidden());
    }

    // -- GET /admin/share-links, POST /admin/share-links/revoke/{id} -----------

    @Test
    void shareLinksPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        createShareToken(file, null, null);
        mockMvc.perform(get("/admin/share-links").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-share-links"))
                .andExpect(model().attributeExists("tokensPage"));
    }

    @Test
    void revokeShareToken_withAdminSession_redirects() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "x".getBytes());
        var token = createShareToken(file, null, null);
        mockMvc.perform(post("/admin/share-links/revoke/" + token.getId()).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/share-links"));
        assertTrue(shareTokenRepository.findById(token.getId()).isEmpty());
    }

    // -- GET /admin/activity ------------------------------------------------

    @Test
    void activityPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/activity").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-activity"))
                .andExpect(model().attributeExists("activityPage", "eventTypes"));
    }

    // -- POST /admin/notification-test -------------------------------------------

    @Test
    void notificationTest_unknownTarget_returns400() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/notification-test").session(session).with(csrf()).param("target", "carrier-pigeon"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Unknown notification target."));
    }

    @Test
    void notificationTest_unauthenticated_redirectsToAdminPassword() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/notification-test").with(csrf()).param("target", "email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
