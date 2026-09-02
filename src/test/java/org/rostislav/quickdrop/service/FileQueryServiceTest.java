package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.PasteRepository;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link FileQueryService}'s pagination, search, visibility, and authorization
 * logic against the real Spring context (repositories + cache).
 */
class FileQueryServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private FileQueryService fileQueryService;
    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private PasteRepository pasteRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private ApplicationSettingsService applicationSettingsService;

    /**
     * The Spring context (and its SQLite DB) is cached and reused across every test
     * class with an identical @SpringBootTest configuration -- see
     * application-test.properties. That means this class does NOT get a pristine
     * table per test method, and other test classes' rows (and even a prior test
     * method's global-settings changes) are visible here. Resetting the settings this
     * class depends on before each test, and scoping every count-sensitive query with
     * a per-test unique tag baked into the query string, keeps assertions correct
     * regardless of what else has run in the shared context.
     */
    @BeforeEach
    void resetSharedGlobalSettings() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setUploadPasswordEnabled(true);
        vm.setEncryptionEnabled(true);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
    }

    private StoredFile persistFile(String name, String description, boolean hidden, boolean deleted) {
        StoredFile file = new StoredFile();
        file.uuid = UUID.randomUUID().toString();
        file.name = name;
        file.description = description;
        file.size = 10;
        file.hidden = hidden;
        file.deleted = deleted;
        return fileRepository.save(file);
    }

    /** A fresh per-test marker so a search query scopes results to just this test's rows,
     *  even though the underlying table is shared with every other test class in this run. */
    private static String uniqueTag() {
        return "tag" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void getVisibleFilesExcludesHiddenAndDeleted() {
        String tag = uniqueTag();
        StoredFile visible = persistFile("visible-" + tag + ".txt", "public file", false, false);
        persistFile("hidden-" + tag + ".txt", "hidden file", true, false);
        persistFile("deleted-" + tag + ".txt", "deleted file", false, true);

        // Tag-scoped so the shared test DB's rows from other test classes don't affect the count.
        Page<StoredFile> page = fileQueryService.getVisibleFiles(PageRequest.of(0, 10), tag);

        assertEquals(1, page.getTotalElements());
        assertEquals(visible.uuid, page.getContent().get(0).uuid);
    }

    @Test
    void getFilesWithDownloadCountsIncludesHiddenButExcludesDeleted() {
        String tag = uniqueTag();
        StoredFile visible = persistFile("v-" + tag + ".txt", "d", false, false);
        StoredFile hidden = persistFile("h-" + tag + ".txt", "d", true, false);
        persistFile("deleted-" + tag + ".txt", "d", false, true);

        Page<FileEntityView> adminPage = fileQueryService.getFilesWithDownloadCounts(PageRequest.of(0, 10), tag);

        assertEquals(2, adminPage.getTotalElements(), "admin listing must include hidden files");
        var uuids = adminPage.getContent().stream().map(v -> v.uuid).toList();
        assertTrue(uuids.contains(visible.uuid));
        assertTrue(uuids.contains(hidden.uuid));
    }

    @Test
    void getDeletedFilesWithDownloadCountsOnlyReturnsDeleted() {
        String tag = uniqueTag();
        persistFile("v-" + tag + ".txt", "d", false, false);
        StoredFile deleted = persistFile("gone-" + tag + ".txt", "d", false, true);

        Page<FileEntityView> deletedPage = fileQueryService.getDeletedFilesWithDownloadCounts(PageRequest.of(0, 10), tag);

        assertEquals(1, deletedPage.getTotalElements());
        assertEquals(deleted.uuid, deletedPage.getContent().get(0).uuid);
    }

    @Test
    void searchMatchesByName() {
        persistFile("unique-quokka-name.txt", "generic description", false, false);
        persistFile("other.txt", "generic description", false, false);

        Page<StoredFile> results = fileQueryService.getVisibleFiles(PageRequest.of(0, 10), "quokka");

        assertEquals(1, results.getTotalElements());
    }

    @Test
    void searchMatchesByDescription() {
        persistFile("plain-name-1.txt", "contains a distinctive marker: zebrafish", false, false);
        persistFile("plain-name-2.txt", "nothing special here", false, false);

        Page<StoredFile> results = fileQueryService.getVisibleFiles(PageRequest.of(0, 10), "zebrafish");

        assertEquals(1, results.getTotalElements());
    }

    @Test
    void searchIsCaseInsensitive() {
        persistFile("CaseSensitiveName.txt", "d", false, false);

        Page<StoredFile> results = fileQueryService.getVisibleFiles(PageRequest.of(0, 10), "casesensitivename");

        assertEquals(1, results.getTotalElements());
    }

    @Test
    void blankQueryReturnsAllVisibleFiles() {
        // Blank means "no filter" so it can't be tag-scoped like the other tests; check
        // membership in a generously-sized page instead of an exact total.
        StoredFile a = persistFile("a-" + uniqueTag() + ".txt", "d", false, false);
        StoredFile b = persistFile("b-" + uniqueTag() + ".txt", "d", false, false);

        Page<StoredFile> results = fileQueryService.getVisibleFiles(PageRequest.of(0, 500), "   ");

        var uuids = results.getContent().stream().map(f -> f.uuid).toList();
        assertTrue(uuids.contains(a.uuid));
        assertTrue(uuids.contains(b.uuid));
    }

    @Test
    void paginationRespectsPageAndSize() {
        String tag = uniqueTag();
        for (int i = 0; i < 5; i++) {
            persistFile("file-" + i + "-" + tag + ".txt", "d", false, false);
        }

        Page<StoredFile> firstPage = fileQueryService.getVisibleFiles(PageRequest.of(0, 2), tag);
        Page<StoredFile> secondPage = fileQueryService.getVisibleFiles(PageRequest.of(1, 2), tag);
        Page<StoredFile> thirdPage = fileQueryService.getVisibleFiles(PageRequest.of(2, 2), tag);

        assertEquals(5, firstPage.getTotalElements());
        assertEquals(3, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, secondPage.getContent().size());
        assertEquals(1, thirdPage.getContent().size());
    }

    @Test
    void pageBeyondLastReturnsEmptyContentNotError() {
        String tag = uniqueTag();
        persistFile("only-one-" + tag + ".txt", "d", false, false);

        Page<StoredFile> farPage = fileQueryService.getVisibleFiles(PageRequest.of(50, 10), tag);

        assertTrue(farPage.getContent().isEmpty());
        assertEquals(1, farPage.getTotalElements());
    }

    @Test
    void checkFilePasswordMatchesCorrectPasswordOnly() {
        StoredFile file = persistFile("secured.txt", "d", false, false);
        file.passwordHash = passwordEncoder.encode("correct-password");
        fileRepository.save(file);

        assertTrue(fileQueryService.checkFilePassword(file.uuid, "correct-password"));
        assertFalse(fileQueryService.checkFilePassword(file.uuid, "wrong-password"));
    }

    @Test
    void checkFilePasswordFalseWhenNoPasswordSet() {
        StoredFile file = persistFile("open.txt", "d", false, false);
        assertFalse(fileQueryService.checkFilePassword(file.uuid, "anything"));
    }

    @Test
    void checkFilePasswordFalseForUnknownUuid() {
        assertFalse(fileQueryService.checkFilePassword("no-such-uuid", "anything"));
    }

    @Test
    void isAuthorizedForFileWithNoPasswordIsAlwaysTrue() {
        StoredFile file = persistFile("open.txt", "d", false, false);
        assertTrue(fileQueryService.isAuthorizedForFile(file.uuid, new MockHttpServletRequest()));
    }

    @Test
    void isAuthorizedForFileWithPasswordRequiresMatchingSessionToken() {
        StoredFile file = persistFile("secured.txt", "d", false, false);
        file.passwordHash = passwordEncoder.encode("pw");
        fileRepository.save(file);

        MockHttpServletRequest noToken = new MockHttpServletRequest();
        assertFalse(fileQueryService.isAuthorizedForFile(file.uuid, noToken));

        String token = UUID.randomUUID().toString();
        sessionService.addFileSessionToken(token, "pw", file.uuid);
        MockHttpServletRequest withToken = new MockHttpServletRequest();
        withToken.getSession(true).setAttribute("file-session-token", token);
        assertTrue(fileQueryService.isAuthorizedForFile(file.uuid, withToken));

        String otherToken = UUID.randomUUID().toString();
        sessionService.addFileSessionToken(otherToken, "pw", "a-different-file-uuid");
        MockHttpServletRequest crossFileRequest = new MockHttpServletRequest();
        crossFileRequest.getSession(true).setAttribute("file-session-token", otherToken);
        assertFalse(fileQueryService.isAuthorizedForFile(file.uuid, crossFileRequest),
                "a session token bound to a different file must not authorize this one");
    }

    @Test
    void isAuthorizedForFileEditOnlyPasteIsAlwaysViewableWithoutSession() {
        Paste paste = new Paste();
        paste.uuid = UUID.randomUUID().toString();
        paste.name = "notes.txt";
        paste.editOnly = true;
        paste.passwordHash = passwordEncoder.encode("edit-pw");
        pasteRepository.save(paste);

        assertTrue(fileQueryService.isAuthorizedForFile(paste.uuid, new MockHttpServletRequest()),
                "edit-only pastes must be publicly viewable regardless of the edit password");
    }

    @Test
    void isAuthorizedToEditImmutablePasteIsAlwaysFalse() {
        Paste paste = new Paste();
        paste.uuid = UUID.randomUUID().toString();
        paste.name = "locked.txt";
        paste.immutable = true;
        pasteRepository.save(paste);

        assertFalse(fileQueryService.isAuthorizedToEdit(paste.uuid, new MockHttpServletRequest()));
    }

    /**
     * IDOR regression (docs/test-reports/security-probes.md SEV-1): a paste with no password
     * has no credential to check ownership against -- only an admin may edit it, matching the
     * precedent FileViewController#isAuthorizedToDelete already sets for the analogous
     * "no password on a file" case. isAuthorizedForFile (view) stays public for the same paste.
     */
    @Test
    void isAuthorizedToEditWithNoPasswordIsAdminOnly() {
        Paste paste = new Paste();
        paste.uuid = UUID.randomUUID().toString();
        paste.name = "open.txt";
        pasteRepository.save(paste);

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        assertFalse(fileQueryService.isAuthorizedToEdit(paste.uuid, anonymous));
        assertTrue(fileQueryService.isAuthorizedForFile(paste.uuid, anonymous));

        String adminToken = sessionService.addAdminToken(UUID.randomUUID().toString());
        MockHttpServletRequest asAdmin = new MockHttpServletRequest();
        asAdmin.getSession(true).setAttribute("admin-session-token", adminToken);
        assertTrue(fileQueryService.isAuthorizedToEdit(paste.uuid, asAdmin));
    }

    @Test
    void isAuthorizedToEditWithPasswordRequiresSessionToken() {
        Paste paste = new Paste();
        paste.uuid = UUID.randomUUID().toString();
        paste.name = "editable.txt";
        paste.passwordHash = passwordEncoder.encode("edit-pw");
        pasteRepository.save(paste);

        assertFalse(fileQueryService.isAuthorizedToEdit(paste.uuid, new MockHttpServletRequest()));

        String token = UUID.randomUUID().toString();
        sessionService.addFileSessionToken(token, "edit-pw", paste.uuid);
        MockHttpServletRequest withToken = new MockHttpServletRequest();
        withToken.getSession(true).setAttribute("file-session-token", token);
        assertTrue(fileQueryService.isAuthorizedToEdit(paste.uuid, withToken));
    }

    @Test
    void shouldEncryptTrueWhenPasswordProvidedAndEncryptionEnabled() {
        UploadRequest request = new UploadRequest(null, false, "pw", false, "f.txt", 1, 1L, null, null, false, null, null, false);
        assertTrue(fileQueryService.shouldEncrypt(request));
    }

    @Test
    void shouldEncryptFalseWithoutPassword() {
        UploadRequest request = new UploadRequest(null, false, null, false, "f.txt", 1, 1L, null, null, false, null, null, false);
        assertFalse(fileQueryService.shouldEncrypt(request));
    }

    @Test
    void shouldEncryptFalseForEditOnlyEvenWithPassword() {
        UploadRequest request = new UploadRequest(null, false, "pw", false, "f.txt", 1, 1L, null, null,
                false, null, null, true, true, false);
        assertFalse(fileQueryService.shouldEncrypt(request), "edit-only content must never be AES-encrypted");
    }

    @Test
    void shouldEncryptFalseWhenEncryptionDisabledGlobally() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setUploadPasswordEnabled(true);
        vm.setEncryptionEnabled(false);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        UploadRequest request = new UploadRequest(null, false, "pw", false, "f.txt", 1, 1L, null, null, false, null, null, false);
        assertFalse(fileQueryService.shouldEncrypt(request));
    }
}
