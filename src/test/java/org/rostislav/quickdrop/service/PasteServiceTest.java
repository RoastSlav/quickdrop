package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.PasteRepository;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link PasteService} CRUD, edit-permission, immutable, and edit-only
 * semantics against the real Spring context (createPaste routes through the real
 * {@link AsyncFileMergeService} single-chunk pipeline).
 */
class PasteServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private PasteService pasteService;
    @Autowired
    private PasteRepository pasteRepository;
    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private ApplicationSettingsService applicationSettingsService;

    /**
     * See FileQueryServiceTest's Javadoc on resetSharedGlobalSettings: the settings
     * row is a shared singleton across every test class in this run, so a prior
     * test elsewhere that disabled encryption/upload-passwords would otherwise leak
     * into these encryption-dependent tests.
     */
    @BeforeEach
    void resetSharedGlobalSettings() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setUploadPasswordEnabled(true);
        vm.setEncryptionEnabled(true);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
    }

    private MockHttpServletRequest requestWithFileSession(String password, String uuid) {
        String token = UUID.randomUUID().toString();
        sessionService.addFileSessionToken(token, password, uuid);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("file-session-token", token);
        return request;
    }

    @Test
    void createPastePersistsPlainTextContent() throws Exception {
        Upload saved = pasteService.createPaste("My Title", "Hello World", "text",
                false, null, false, false, new MockHttpServletRequest());

        assertNotNull(saved);
        assertTrue(saved.isPaste());
        assertEquals("My Title.txt", saved.name);
        assertEquals("Hello World", pasteService.getPasteContent(saved.uuid, new MockHttpServletRequest()));
    }

    @Test
    void createPasteMarkdownGetsMdExtension() throws Exception {
        Upload saved = pasteService.createPaste("Notes", "# heading", "markdown",
                false, null, false, false, new MockHttpServletRequest());

        assertNotNull(saved);
        assertTrue(saved.name.endsWith(".md"));
    }

    @Test
    void createPasteWithPasswordEncryptsContentAndRequiresSessionToRead() throws Exception {
        Upload saved = pasteService.createPaste("Secret", "classified", "text",
                false, "paste-pw", false, false, new MockHttpServletRequest());

        assertNotNull(saved);
        assertTrue(saved.encrypted, "a password-protected, non-edit-only paste should be encrypted");

        assertNull(pasteService.getPasteContent(saved.uuid, new MockHttpServletRequest()),
                "reading an encrypted paste without a valid session must fail, not leak content");

        String content = pasteService.getPasteContent(saved.uuid, requestWithFileSession("paste-pw", saved.uuid));
        assertEquals("classified", content);
    }

    @Test
    void createPasteEditOnlyIsNeverEncryptedEvenWithPassword() throws Exception {
        Upload saved = pasteService.createPaste("Public Notes", "visible to all", "text",
                false, "edit-pw", false, true, new MockHttpServletRequest());

        assertNotNull(saved);
        assertFalse(saved.encrypted, "edit-only content must be stored in plaintext so it can be viewed without a password");
        assertTrue(saved.isEditOnly());
        assertEquals("visible to all", pasteService.getPasteContent(saved.uuid, new MockHttpServletRequest()));
    }

    @Test
    void updatePasteOverwritesContentAndLogsEdit() throws Exception {
        Upload saved = pasteService.createPaste("Draft", "v1", "text", false, null, false, false, new MockHttpServletRequest());

        Paste updated = pasteService.updatePaste(saved.uuid, "Final", "v2", "text", false, false, null, new MockHttpServletRequest());

        assertNotNull(updated);
        assertEquals("v2", pasteService.getPasteContent(saved.uuid, new MockHttpServletRequest()));
        assertEquals(1, activityLogRepository.countByFileAndType(saved.uuid, EventType.PASTE_EDIT));
    }

    @Test
    void updatePasteReturnsNullForImmutablePaste() throws Exception {
        Upload saved = pasteService.createPaste("Locked", "original", "text", false, null, true, false, new MockHttpServletRequest());

        Paste result = pasteService.updatePaste(saved.uuid, "New Title", "changed", "text", false, false, null, new MockHttpServletRequest());

        assertNull(result);
        assertEquals("original", pasteService.getPasteContent(saved.uuid, new MockHttpServletRequest()));
    }

    @Test
    void updatePasteReturnsNullForUuidBelongingToAFile() {
        StoredFile file = new StoredFile();
        file.uuid = UUID.randomUUID().toString();
        file.name = "not-a-paste.bin";
        file.size = 1;
        fileRepository.save(file);

        assertDoesNotThrow(() -> {
            Paste result = pasteService.updatePaste(file.uuid, "t", "c", "text", false, false, null, new MockHttpServletRequest());
            assertNull(result);
        });
    }

    @Test
    void updatePasteSetImmutableRatchetsPermanently() throws Exception {
        Upload saved = pasteService.createPaste("Ratchet", "v1", "text", false, null, false, false, new MockHttpServletRequest());

        Paste firstEdit = pasteService.updatePaste(saved.uuid, "Ratchet", "v2", "text", false, true, null, new MockHttpServletRequest());
        assertNotNull(firstEdit);
        assertTrue(firstEdit.immutable);

        Paste secondEdit = pasteService.updatePaste(saved.uuid, "Ratchet", "v3", "text", false, false, null, new MockHttpServletRequest());
        assertNull(secondEdit, "once ratcheted immutable, no further edits may succeed");
        assertEquals("v2", pasteService.getPasteContent(saved.uuid, new MockHttpServletRequest()));
    }

    @Test
    void updatePasteOnEncryptedPasteWithoutSessionThrows() throws Exception {
        Upload saved = pasteService.createPaste("Secret", "hidden", "text", false, "pw", false, false, new MockHttpServletRequest());

        assertThrows(IllegalArgumentException.class, () ->
                pasteService.updatePaste(saved.uuid, "Secret", "new content", "text", false, false, null, new MockHttpServletRequest()));
    }

    /**
     * Blank password on edit leaves the CURRENT password state untouched -- content changes,
     * but the paste is still decryptable with the SAME original password afterward. This is
     * the safe default: a password field can never be pre-filled with the real value, so
     * treating blank as "remove" would silently strip protection on every content-only edit.
     */
    @Test
    void updatePasteOnEncryptedPasteWithValidSessionSucceeds() throws Exception {
        Upload saved = pasteService.createPaste("Secret", "hidden", "text", false, "pw", false, false, new MockHttpServletRequest());

        Paste updated = pasteService.updatePaste(saved.uuid, "Secret", "new content", "text", false, false, null,
                requestWithFileSession("pw", saved.uuid));

        assertNotNull(updated);
        assertTrue(updated.encrypted, "blank password on edit must not remove existing protection");
        assertEquals("new content", pasteService.getPasteContent(saved.uuid, requestWithFileSession("pw", saved.uuid)));
    }

    /**
     * Regression for docs/test-reports/journeys.md SEV-3: pastes previously could never be
     * password-protected after creation -- the edit form had no password field and
     * updatePaste() didn't even accept the parameter.
     */
    @Test
    void updatePasteCanAddPasswordToPreviouslyUnprotectedPaste() throws Exception {
        Upload saved = pasteService.createPaste("Note", "hello", "text", false, null, false, false, new MockHttpServletRequest());
        assertFalse(((Paste) saved).encrypted);

        Paste updated = pasteService.updatePaste(saved.uuid, "Note", "hello", "text", false, false, "new-pw",
                new MockHttpServletRequest());

        assertNotNull(updated);
        assertTrue(updated.encrypted);
        assertNotNull(updated.passwordHash);
        assertEquals("hello", pasteService.getPasteContent(saved.uuid, requestWithFileSession("new-pw", saved.uuid)));
    }

    @Test
    void updatePasteCanChangeExistingPasswordToANewOne() throws Exception {
        Upload saved = pasteService.createPaste("Secret", "hidden", "text", false, "old-pw", false, false, new MockHttpServletRequest());

        Paste updated = pasteService.updatePaste(saved.uuid, "Secret", "hidden", "text", false, false, "new-pw",
                requestWithFileSession("old-pw", saved.uuid));

        assertNotNull(updated);
        assertTrue(updated.encrypted);
        assertEquals("hidden", pasteService.getPasteContent(saved.uuid, requestWithFileSession("new-pw", saved.uuid)),
                "must be decryptable with the NEW password");
    }

    @Test
    void logPasteViewRecordsAnActivityLogEntry() throws Exception {
        Upload saved = pasteService.createPaste("Viewed", "content", "text", false, null, false, false, new MockHttpServletRequest());

        pasteService.logPasteView(saved.uuid, new MockHttpServletRequest());

        assertEquals(1, activityLogRepository.countByFileAndType(saved.uuid, EventType.PASTE_VIEW));
    }

    @Test
    void logPasteViewIsANoOpForUnknownUuid() {
        assertDoesNotThrow(() -> pasteService.logPasteView("no-such-uuid", new MockHttpServletRequest()));
    }

    @Test
    void paginatedPastesExcludeDeletedAndDeletedListingIncludesOnlyDeleted() throws Exception {
        // Tagged title so the search-scoped query finds just these two amid the shared test DB.
        String tag = "tag" + UUID.randomUUID().toString().replace("-", "");
        Upload live = pasteService.createPaste("Live-" + tag, "c1", "text", false, null, false, false, new MockHttpServletRequest());
        Upload toDelete = pasteService.createPaste("Gone-" + tag, "c2", "text", false, null, false, false, new MockHttpServletRequest());
        Paste deletedPaste = pasteRepository.findByUUID(toDelete.uuid).orElseThrow();
        deletedPaste.deleted = true;
        pasteRepository.save(deletedPaste);

        Page<org.rostislav.quickdrop.model.PasteEntityView> livePage = pasteService.getPaginatedPastes(PageRequest.of(0, 10), tag);
        Page<org.rostislav.quickdrop.model.PasteEntityView> deletedPage = pasteService.getDeletedPaginatedPastes(PageRequest.of(0, 10), tag);

        assertTrue(livePage.getContent().stream().anyMatch(p -> p.uuid.equals(live.uuid)));
        assertTrue(livePage.getContent().stream().noneMatch(p -> p.uuid.equals(toDelete.uuid)));
        assertTrue(deletedPage.getContent().stream().anyMatch(p -> p.uuid.equals(toDelete.uuid)));
    }

    @Test
    void analyticsAggregatesReflectCreatedPastes() throws Exception {
        pasteService.createPaste("A", "12345", "text", false, null, false, false, new MockHttpServletRequest());
        pasteService.createPaste("B", "1234567890", "markdown", false, null, false, false, new MockHttpServletRequest());

        assertTrue(pasteService.getPasteCount() >= 2);
        assertTrue(pasteService.getMarkdownPasteCount() >= 1);
        assertTrue(pasteService.getAveragePasteLength() > 0);
    }
}
