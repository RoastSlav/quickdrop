package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasteViewControllerTest extends ControllerTestSupport {

    // -- GET /file/paste/new ---------------------------------------------------

    @Test
    void newPastePage_pastebinEnabled_returns200() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/paste/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("pastebin"))
                .andExpect(model().attribute("isEditMode", false));
    }

    @Test
    @DirtiesContext
    void newPastePage_pastebinDisabled_anonymousRedirectsHome() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setPastebinEnabled(false));
        mockMvc.perform(get("/file/paste/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DirtiesContext
    void newPastePage_pastebinDisabled_adminSessionStillAllowed() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setPastebinEnabled(false));
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/file/paste/new").session(session))
                .andExpect(status().isOk());
    }

    // -- GET /file/paste/edit/{uuid} --------------------------------------------

    /**
     * IDOR regression (docs/test-reports/security-probes.md SEV-1): a paste with no password
     * has no credential to check ownership against, so -- matching the precedent
     * FileViewController#isAuthorizedToDelete already sets for the analogous "no password on
     * a file" case -- only an admin may edit it. Anonymous callers must be denied even though
     * the paste is publicly viewable.
     */
    @Test
    void editPastePage_mutableUnprotectedPaste_anonymousIsDenied() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "hello", null, false, false);
        mockMvc.perform(get("/file/paste/edit/" + paste.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + paste.uuid + "?editMode=true"));
    }

    @Test
    void editPastePage_mutableUnprotectedPaste_adminSessionStillAllowed() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "hello", null, false, false);
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/file/paste/edit/" + paste.uuid).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("pastebin"))
                .andExpect(model().attribute("isEditMode", true));
    }

    @Test
    void editPastePage_unknownUuid_returns404() throws Exception {
        // Same composition detail as FileViewControllerTest#filePage_unknownUuid_returns404:
        // FilePasswordInterceptor also covers /file/paste/edit/{uuid} and 404s an unknown uuid
        // before PasteViewController's own "unknown uuid -> redirect /file/list" branch runs.
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/paste/edit/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editPastePage_nonPasteUuid_redirectsToFilePage() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "x".getBytes());
        mockMvc.perform(get("/file/paste/edit/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + file.uuid));
    }

    @Test
    void editPastePage_immutablePaste_redirectsToView() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("locked.txt", "hello", null, true, false);
        mockMvc.perform(get("/file/paste/edit/" + paste.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));
    }

    @Test
    void editPastePage_deletedPaste_redirectsToView() throws Exception {
        // createDeletedPaste() sets no password, so FilePasswordInterceptor lets this through
        // (the row still exists -- deleted is a soft-delete flag, not a query filter) without
        // needing a session; the deleted-check inside showPasteEditPage itself is what redirects.
        ensureAdminPasswordSet();
        Paste paste = createDeletedPaste("gone.txt", "hello");
        mockMvc.perform(get("/file/paste/edit/" + paste.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));
    }

    @Test
    void editPastePage_passwordProtectedPaste_anonymousRedirectsToPasswordPage() throws Exception {
        // FilePasswordInterceptor also covers /file/paste/edit/{uuid} and performs its own
        // (coarser) password-session check first, redirecting to the plain password page --
        // PasteViewController's own more specific "?editMode=true" redirect is unreachable here.
        ensureAdminPasswordSet();
        Paste paste = createPaste("secret.txt", "hello", "pastepw", false, false);
        mockMvc.perform(get("/file/paste/edit/" + paste.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + paste.uuid));
    }

    // -- POST /file/paste (create) -----------------------------------------------

    @Test
    void createPaste_validContent_redirectsToPasteView() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/file/paste").with(csrf())
                        .param("title", "My Note")
                        .param("content", "hello world")
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DirtiesContext
    void createPaste_pastebinDisabled_anonymousRedirectsHome() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setPastebinEnabled(false));
        mockMvc.perform(post("/file/paste").with(csrf())
                        .param("title", "t").param("content", "c"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DirtiesContext
    void createPaste_uploadPasswordDisabled_rejectsSubmittedPassword() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadPasswordEnabled(false));

        mockMvc.perform(post("/file/paste").with(csrf())
                        .param("title", "t").param("content", "c").param("password", "shouldnt-be-allowed"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/paste/new"));

        assertTrue(pasteRepository.findAll().stream().noneMatch(p -> "t.txt".equals(p.name)),
                "paste must not be created when a password was submitted with upload passwords disabled");
    }

    @Test
    void createPaste_withoutCsrf_isForbidden() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/file/paste").param("title", "t").param("content", "c"))
                .andExpect(status().isForbidden());
    }

    /**
     * The other tests in this class use {@link ControllerTestSupport#createPaste} to seed
     * pastes directly, bypassing the real {@code POST /file/paste} endpoint entirely. This is
     * the only test that goes through that endpoint and checks what actually reaches disk:
     * that content isn't escaped, re-encoded, or otherwise mangled on the way in. Content
     * sanitization for display is a client-side concern (DOMPurify) and is not what this
     * asserts -- markup surviving verbatim is the expected, correct behavior here, not a
     * vulnerability.
     */
    @Test
    void createPaste_viaRealEndpoint_persistsContentVerbatim() throws Exception {
        ensureAdminPasswordSet();
        String content = "<script>alert(1)</script> & \"quotes\" & 'apostrophes' & unicode: üñî";

        mockMvc.perform(post("/file/paste").with(csrf())
                        .param("title", "verbatim-test")
                        .param("content", content)
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection());

        var all = pasteRepository.findAll();
        Paste stored = all.stream()
                .filter(p -> "verbatim-test.txt".equals(p.name))
                .max(java.util.Comparator.comparing(p -> p.id))
                .orElseThrow();
        String storedContent = java.nio.file.Files.readString(storageDir.resolve(stored.uuid));
        assertEquals(content, storedContent);
    }

    // -- POST /file/paste/edit/{uuid} --------------------------------------------

    /**
     * IDOR regression (docs/test-reports/security-probes.md SEV-1): anonymous edit of a
     * no-password paste must be denied and the content left untouched -- this is the exact
     * finding ("Anonymous users can silently overwrite the content of any non-password-protected
     * paste"), reproduced here at the MockMvc layer.
     */
    @Test
    void updatePaste_mutableUnprotectedPaste_anonymousIsDenied() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "old content", null, false, false);
        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf())
                        .param("title", "note")
                        .param("content", "pwned")
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + paste.uuid + "?editMode=true"));

        String storedContent = java.nio.file.Files.readString(storageDir.resolve(paste.uuid));
        assertEquals("old content", storedContent);
    }

    @Test
    void updatePaste_mutableUnprotectedPaste_adminSessionSucceeds() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "old content", null, false, false);
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf()).session(session)
                        .param("title", "note")
                        .param("content", "new content")
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));

        String storedContent = java.nio.file.Files.readString(storageDir.resolve(paste.uuid));
        assertEquals("new content", storedContent);
    }

    @Test
    void updatePaste_immutablePaste_redirectsToViewWithoutChange() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("locked.txt", "old content", null, true, false);
        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf())
                        .param("title", "locked")
                        .param("content", "attempted change")
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));
    }

    /**
     * IDOR regression (GHSA-q8mc-vr6g-xjwg pattern): anonymous edit of a password-protected
     * paste must be denied and redirected to the password page, not silently applied.
     */
    @Test
    void updatePaste_passwordProtectedPaste_anonymousIsDenied() throws Exception {
        // Same interceptor-first composition as the GET edit page above.
        ensureAdminPasswordSet();
        Paste paste = createPaste("secret.txt", "old content", "editpw", false, false);
        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf())
                        .param("title", "secret")
                        .param("content", "hacked content")
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + paste.uuid));

        String storedContent = java.nio.file.Files.readString(storageDir.resolve(paste.uuid));
        assertEquals("old content", storedContent);
    }

    @Test
    @DirtiesContext
    void updatePaste_pastebinDisabled_anonymousRedirectsHome() throws Exception {
        // No password, so FilePasswordInterceptor lets this through before the pastebin
        // check even runs -- pastebin-disabled must still gate the controller itself.
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "old content", null, false, false);
        updateSettings(s -> s.setPastebinEnabled(false));

        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf())
                        .param("title", "note").param("content", "attempted change").param("syntax", "text"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        String storedContent = java.nio.file.Files.readString(storageDir.resolve(paste.uuid));
        assertEquals("old content", storedContent);
    }

    @Test
    @DirtiesContext
    void updatePaste_uploadPasswordDisabled_rejectsSubmittedPassword() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "old content", null, false, false);
        updateSettings(s -> s.setUploadPasswordEnabled(false));
        MockHttpSession session = adminSession();

        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf()).session(session)
                        .param("title", "note").param("content", "new content").param("syntax", "text")
                        .param("password", "shouldnt-be-allowed"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/paste/edit/" + paste.uuid));

        String storedContent = java.nio.file.Files.readString(storageDir.resolve(paste.uuid));
        assertEquals("old content", storedContent, "update must be rejected, not partially applied, when a password is submitted with upload passwords disabled");
    }

    @Test
    void updatePaste_withoutCsrf_isForbidden() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "content", null, false, false);
        mockMvc.perform(post("/file/paste/edit/" + paste.uuid)
                        .param("title", "note").param("content", "x"))
                .andExpect(status().isForbidden());
    }

    /**
     * Regression for docs/test-reports/journeys.md SEV-3: pastes previously could never be
     * password-protected after creation. End-to-end through the real controller/service,
     * confirming the new password actually gates a subsequent view.
     */
    @Test
    void updatePaste_adminSessionCanAddPasswordToUnprotectedPaste() throws Exception {
        ensureAdminPasswordSet();
        Paste paste = createPaste("note.txt", "old content", null, false, false);
        MockHttpSession session = adminSession();

        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf()).session(session)
                        .param("title", "note")
                        .param("content", "still secret")
                        .param("syntax", "text")
                        .param("password", "brand-new-pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));

        Paste reloaded = pasteRepository.findByUUID(paste.uuid).orElseThrow();
        assertTrue(reloaded.encrypted);
        assertNotNull(reloaded.passwordHash);
        // Now password-protected: an anonymous GET must be gated.
        mockMvc.perform(get("/file/" + paste.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + paste.uuid));
    }

    @Test
    void updatePaste_ownerSessionCanChangeExistingPassword() throws Exception {
        // fileSession(uuid, password) registers a token WITHOUT verifying the password against
        // the entity (that verification only happens in the real POST /file/password login
        // flow) -- so proving the password actually changed requires exercising that real
        // login endpoint, not the test helper's shortcut token.
        ensureAdminPasswordSet();
        Paste paste = createPaste("secret.txt", "old content", "old-pw", false, false);
        MockHttpSession ownerSession = fileSession(paste.uuid, "old-pw");

        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf()).session(ownerSession)
                        .param("title", "secret")
                        .param("content", "updated content")
                        .param("syntax", "text")
                        .param("password", "new-pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));

        // The OLD password must no longer log in; the NEW one must.
        mockMvc.perform(post("/file/password").with(csrf())
                        .param("uuid", paste.uuid)
                        .param("password", "old-pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + paste.uuid));

        mockMvc.perform(post("/file/password").with(csrf())
                        .param("uuid", paste.uuid)
                        .param("password", "new-pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));
    }

    /**
     * Blank password on edit must leave the paste's current protection untouched -- content
     * changes, but the same original password still logs in afterward.
     */
    @Test
    void updatePaste_blankPasswordLeavesExistingProtectionUnchanged() throws Exception {
        // NB: ControllerTestSupport#createPaste is a fast test-setup shortcut -- it sets
        // passwordHash directly but never sets `encrypted` (content is written plaintext, not
        // through the real AES pipeline), unlike the real create flow. So this asserts via the
        // real POST /file/password login flow (which only checks passwordHash) rather than the
        // `encrypted` field, which this fixture never sets true in the first place.
        ensureAdminPasswordSet();
        Paste paste = createPaste("secret.txt", "old content", "keep-me", false, false);
        MockHttpSession session = fileSession(paste.uuid, "keep-me");

        mockMvc.perform(post("/file/paste/edit/" + paste.uuid).with(csrf()).session(session)
                        .param("title", "secret")
                        .param("content", "new content, same password")
                        .param("syntax", "text"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));

        mockMvc.perform(post("/file/password").with(csrf())
                        .param("uuid", paste.uuid)
                        .param("password", "keep-me"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/" + paste.uuid));
    }
}
