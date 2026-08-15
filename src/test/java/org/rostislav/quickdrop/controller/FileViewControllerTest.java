package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileViewControllerTest extends ControllerTestSupport {

    // -- GET /file/upload -----------------------------------------------------

    @Test
    void uploadPage_defaultSettings_returns200() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/upload"))
                .andExpect(status().isOk())
                .andExpect(view().name("upload"));
    }

    @Test
    @DirtiesContext
    void uploadPage_uploadsDisabled_redirectsHome() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadEnabled(false));
        mockMvc.perform(get("/file/upload"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DirtiesContext
    void uploadPage_adminOnly_adminSessionCanAccess() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadAdminOnly(true));
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/file/upload").session(session))
                .andExpect(status().isOk());
    }

    // -- GET /file/list -----------------------------------------------------

    @Test
    void listFiles_defaultSettings_returns200() throws Exception {
        ensureAdminPasswordSet();
        createFile("a.txt", "hi".getBytes());
        mockMvc.perform(get("/file/list"))
                .andExpect(status().isOk())
                .andExpect(view().name("listFiles"))
                .andExpect(model().attributeExists("filesPage"));
    }

    @Test
    @DirtiesContext
    void listFiles_disabled_anonymousRedirectsHome() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setFileListPageEnabled(false));
        mockMvc.perform(get("/file/list"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    // -- GET /file/{uuid} -----------------------------------------------------

    @Test
    void filePage_existingFile_returns200() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello".getBytes());
        mockMvc.perform(get("/file/" + file.uuid))
                .andExpect(status().isOk())
                .andExpect(view().name("fileView"))
                .andExpect(model().attribute("isDeleted", false));
    }

    @Test
    void filePage_unknownUuid_returns404() throws Exception {
        // FileViewController#filePage has its own "unknown uuid -> redirect /file/list" branch,
        // but it's unreachable in practice: FilePasswordInterceptor covers /file/{uuid} too and
        // already returns 404 for a nonexistent uuid before the controller ever runs. Documented
        // here rather than asserting the (dead) controller-level redirect.
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void filePage_deletedFile_anonymousRedirectsToList() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createDeletedFile("a.txt", "hello".getBytes());
        mockMvc.perform(get("/file/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/list"));
    }

    @Test
    void filePage_deletedFile_adminSeesIt() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createDeletedFile("a.txt", "hello".getBytes());
        mockMvc.perform(get("/file/" + file.uuid).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isDeleted", true));
    }

    @Test
    void filePage_pasteUuid_rendersPasteView() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "hello paste", null, false, false);
        mockMvc.perform(get("/file/" + paste.uuid))
                .andExpect(status().isOk())
                .andExpect(view().name("pasteView"))
                .andExpect(model().attribute("pasteContent", "hello paste"));
    }

    @Test
    void filePage_passwordProtectedFile_anonymousRedirectsToPasswordPage() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hello".getBytes(), "filepw");
        mockMvc.perform(get("/file/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    // -- GET /file/preview/{uuid} ---------------------------------------------

    @Test
    void previewFile_unauthorizedPasswordFile_isBlockedByInterceptor() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hello".getBytes(), "filepw");
        mockMvc.perform(get("/file/preview/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    @Test
    void previewFile_unknownUuid_returns404() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/preview/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    // -- POST /file/download/log/{uuid} ------------------------------------------

    @Test
    void logDownload_authorizedPlainFile_returns204() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(post("/file/download/log/" + file.uuid).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void logDownload_unauthorizedPasswordFile_isBlockedByInterceptor() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "filepw");
        mockMvc.perform(post("/file/download/log/" + file.uuid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    // -- GET /file/history/{uuid} -----------------------------------------------
    // Regression: /file/history/* used to be unconditionally covered by
    // AdminPasswordInterceptor, making it admin-only regardless of the file's own password
    // state and leaving FileViewController#viewFileHistory's own "file session OR admin
    // session" check unreachable dead code. WebConfig now excludes this route from both
    // blanket interceptors so that check is the sole authority.

    @Test
    void fileHistory_adminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(get("/file/history/" + file.uuid).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("file-history"))
                .andExpect(model().attributeExists("file", "actionLogs"));
    }

    @Test
    void fileHistory_anonymousOnPlainNonPasswordFile_returns200() throws Exception {
        // A non-password file's history is publicly viewable, consistent with the file
        // itself -- the controller's own auth-check block is skipped entirely when
        // passwordHash is null.
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(get("/file/history/" + file.uuid))
                .andExpect(status().isOk())
                .andExpect(view().name("file-history"));
    }

    @Test
    void fileHistory_anonymousOnPasswordProtectedFile_redirectsToPasswordPage() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "historypw");
        mockMvc.perform(get("/file/history/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    /**
     * This is the actual fix: a valid file-session token for a password-protected file's own
     * uuid can now reach its history without also needing an admin session.
     */
    @Test
    void fileHistory_validOwnFileSession_returns200() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "historypw");
        MockHttpSession session = fileSession(file.uuid, "historypw");
        mockMvc.perform(get("/file/history/" + file.uuid).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("file-history"));
    }

    // -- POST /file/password, GET /file/password/{uuid} -------------------------

    @Test
    void checkPassword_correctPassword_redirectsToFileView() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "correct-pw");
        mockMvc.perform(post("/file/password").with(csrf())
                        .param("uuid", file.uuid)
                        .param("password", "correct-pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + file.uuid));
    }

    @Test
    void checkPassword_wrongPassword_redirectsBackWithFlash() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "correct-pw");
        mockMvc.perform(post("/file/password").with(csrf())
                        .param("uuid", file.uuid)
                        .param("password", "wrong-pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    @Test
    void passwordPage_returns200() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "pw");
        mockMvc.perform(get("/file/password/" + file.uuid))
                .andExpect(status().isOk())
                .andExpect(view().name("file-password"))
                .andExpect(model().attribute("uuid", file.uuid));
    }

    // -- GET /file/download/{uuid} ------------------------------------------

    @Test
    void downloadFile_authorizedPlainFile_streamsContent() throws Throwable {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hello world".getBytes());
        retryOnMockMvcAsyncHeaderRace(() -> {
            MvcResult result = mockMvc.perform(get("/file/download/" + file.uuid))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes("hello world".getBytes()));
        });
    }

    @Test
    void downloadFile_unauthorizedPasswordFile_redirectsToPasswordPage() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "pw");
        mockMvc.perform(get("/file/download/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    // -- POST /file/extend/{uuid} ------------------------------------------------
    // Regression check for the IDOR pattern (GHSA-q8mc-vr6g-xjwg): a no-password upload has
    // no credential to check a session token against, so -- matching the precedent
    // isAuthorizedToDelete/isAuthorizedToEdit already set -- only an admin session may extend
    // it. See FileLifecycleService#canMutateNoPasswordUpload.

    @Test
    void extendFile_anonymousOnPlainFile_isDenied() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        java.time.LocalDate original = java.time.LocalDate.now().minusDays(10);
        file.uploadDate = original;
        fileRepository.save(file);

        mockMvc.perform(post("/file/extend/" + file.uuid).with(csrf()));

        StoredFile after = fileRepository.findByUUID(file.uuid).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(original, after.uploadDate);
    }

    @Test
    void extendFile_adminSession_succeeds() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        file.uploadDate = java.time.LocalDate.now().minusDays(10);
        fileRepository.save(file);
        MockHttpSession session = adminSession();

        mockMvc.perform(post("/file/extend/" + file.uuid).session(session).with(csrf()));

        StoredFile after = fileRepository.findByUUID(file.uuid).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.now(), after.uploadDate);
    }

    // -- POST /file/delete/{uuid} ------------------------------------------------

    @Test
    void deleteFile_anonymousOnPlainFile_isDenied() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(post("/file/delete/" + file.uuid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + file.uuid));
        assertFalse(fileRepository.findByUUID(file.uuid).orElseThrow().deleted);
    }

    @Test
    void deleteFile_crossFileSessionToken_isDenied_regressionForGHSA_q8mc() throws Exception {
        ensureAdminPasswordSet();
        StoredFile fileA = createFile("a.txt", "hi".getBytes(), "pwA");
        StoredFile fileB = createFile("b.txt", "bye".getBytes(), "pwB");
        // Session token is valid, but bound to file A -- attempt to delete file B with it.
        MockHttpSession sessionForA = fileSession(fileA.uuid, "pwA");

        // FilePasswordInterceptor covers /file/delete/{uuid} too and performs the exact same
        // uuid-scoped session check as FileViewController#isAuthorizedToDelete -- since file B
        // has a password and the session token isn't valid for B, the interceptor redirects to
        // file B's password page before the controller's own IDOR check is ever reached. Both
        // layers agree the request must be denied; asserting the interceptor's redirect target
        // here since that's the one actually observed.
        mockMvc.perform(post("/file/delete/" + fileB.uuid).session(sessionForA).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + fileB.uuid));

        assertFalse(fileRepository.findByUUID(fileB.uuid).orElseThrow().deleted,
                "a session token scoped to a different file must not authorize deleting this one");
    }

    @Test
    void deleteFile_validOwnFileSession_succeeds() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "pw");
        MockHttpSession session = fileSession(file.uuid, "pw");
        mockMvc.perform(post("/file/delete/" + file.uuid).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/list"));
        assertTrue(fileRepository.findByUUID(file.uuid).orElseThrow().deleted);
    }

    @Test
    void deleteFile_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(post("/file/delete/" + file.uuid).session(session))
                .andExpect(status().isForbidden());
    }

    // -- GET /file/search -----------------------------------------------------

    @Test
    void searchFiles_redirectsToListWithQuery() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/search").param("query", "report"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/list?query=report&page=0&size=20"));
    }

    @Test
    void searchFiles_blankQuery_redirectsToListWithoutQuery() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/search").param("query", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/list"));
    }

    // -- POST /file/keep-indefinitely/{uuid}, /file/toggle-hidden/{uuid} --------

    @Test
    void keepIndefinitely_adminSession_updatesFlag() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/file/keep-indefinitely/" + file.uuid).session(session).with(csrf()).param("keepIndefinitely", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + file.uuid));
        assertTrue(fileRepository.findByUUID(file.uuid).orElseThrow().keepIndefinitely);
    }

    /**
     * IDOR regression: a no-password file has no credential to check ownership against, so
     * only an admin session may toggle keepIndefinitely — matching the extend/delete/paste-edit
     * precedent. This holds even though {@code isKeepIndefinitelyAdminOnly} defaults to
     * {@code false} (that setting only ever widens the admin-only case, never narrows this one).
     */
    @Test
    void keepIndefinitely_anonymousOnPlainFile_isDenied() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(post("/file/keep-indefinitely/" + file.uuid).with(csrf()).param("keepIndefinitely", "true"));
        assertFalse(fileRepository.findByUUID(file.uuid).orElseThrow().keepIndefinitely);
    }

    @Test
    @DirtiesContext
    void keepIndefinitely_whenAdminOnlySetting_anonymousIsBlocked() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setKeepIndefinitelyAdminOnly(true));
        StoredFile file = createFile("a.txt", "hi".getBytes());
        mockMvc.perform(post("/file/keep-indefinitely/" + file.uuid).with(csrf()).param("keepIndefinitely", "true"));
        assertFalse(fileRepository.findByUUID(file.uuid).orElseThrow().keepIndefinitely,
                "keepIndefinitelyAdminOnly=true must block anonymous toggling");
    }

    @Test
    void toggleHidden_adminSession_updatesFlag() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        boolean before = file.hidden;
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/file/toggle-hidden/" + file.uuid).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + file.uuid));
        assertTrue(fileRepository.findByUUID(file.uuid).orElseThrow().hidden != before);
    }

    /**
     * IDOR regression: a no-password file has no credential to check ownership against, so
     * only an admin session may toggle hidden — matching the extend/delete/paste-edit precedent.
     */
    @Test
    void toggleHidden_anonymousOnPlainFile_isDenied() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        boolean before = file.hidden;
        mockMvc.perform(post("/file/toggle-hidden/" + file.uuid).with(csrf()));
        assertTrue(fileRepository.findByUUID(file.uuid).orElseThrow().hidden == before);
    }

    @Test
    @DirtiesContext
    void toggleHidden_whenAdminOnlySetting_anonymousIsBlocked() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setHideFromListAdminOnly(true));
        StoredFile file = createFile("a.txt", "hi".getBytes());
        boolean before = file.hidden;
        mockMvc.perform(post("/file/toggle-hidden/" + file.uuid).with(csrf()));
        assertTrue(fileRepository.findByUUID(file.uuid).orElseThrow().hidden == before,
                "hideFromListAdminOnly=true must block anonymous toggling");
    }
}
