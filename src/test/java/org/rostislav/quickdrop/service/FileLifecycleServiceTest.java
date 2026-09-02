package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.ShortLinkResult;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link FileLifecycleService} against the real Spring context
 * (repositories, cache manager, session service) with storage redirected to a
 * {@code @TempDir} by {@link QuickdropIntegrationTest}.
 */
class FileLifecycleServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private FileLifecycleService fileLifecycleService;
    @Autowired
    private FileQueryService fileQueryService;
    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private UploadRepository uploadRepository;
    @Autowired
    private ShortLinkRepository shareTokenRepository;
    @Autowired
    private ActivityLogRepository activityLogRepository;
    @Autowired
    private ApplicationSettingsService applicationSettingsService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private StorageService storageService;
    @Autowired
    private EncryptionService encryptionService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CacheManager cacheManager;

    /**
     * The admin-only gate flags are process-wide singleton settings shared (via
     * Spring's test-context caching) across every test method in this class -- and
     * every other test class with an identical @SpringBootTest configuration. Reset
     * them to the "off" default before each test so a gate left "on" by one test
     * (or a differently-ordered run) can't make an unrelated test fail.
     */
    @BeforeEach
    void resetAdminOnlyGates() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setHideFromListAdminOnly(false);
        vm.setKeepIndefinitelyAdminOnly(false);
        vm.setUploadPasswordEnabled(true);
        vm.setEncryptionEnabled(true);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
    }

    private StoredFile persistPlainFile(String uuid) {
        StoredFile file = new StoredFile();
        file.uuid = uuid;
        file.name = "doc-" + uuid + ".txt";
        file.size = 100;
        file.hidden = false;
        return fileRepository.save(file);
    }

    private MockHttpServletRequest requestWithAdminSession() {
        String token = UUID.randomUUID().toString();
        sessionService.addAdminToken(token);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("admin-session-token", token);
        return request;
    }

    @Test
    void saveFileValidatesAndPersistsWithUploadEvent() {
        String uuid = UUID.randomUUID().toString();
        UploadRequest request = new UploadRequest("a description", false, null, false,
                "hello.txt", 1, 5L, "1.2.3.4", "JUnit-Agent",
                false, null, null, false);

        Upload saved = fileLifecycleService.saveFile(request, uuid);

        assertNotNull(saved);
        assertEquals("hello.txt", saved.name);
        assertEquals(uuid, saved.uuid);
        assertFalse(saved.encrypted);
        assertTrue(uploadRepository.findByUUID(uuid).isPresent());
        assertEquals(1, activityLogRepository.countByFileAndType(uuid, EventType.UPLOAD));
    }

    @Test
    void saveFileReturnsNullForNullRequest() {
        // validateObjects(uploadRequest) validates the UploadRequest reference itself
        // (and, incidentally, that any String *arguments passed directly* aren't
        // blank) -- it does NOT reach into the request's individual fields, so a
        // request with a null fileName but a non-null request object passes
        // validation. The only way saveFile's validateObjects guard actually trips is
        // a null request itself.
        assertNull(fileLifecycleService.saveFile(null, UUID.randomUUID().toString()));
    }

    @Test
    void saveFileEncryptsOnlyWhenPasswordProvidedAndEncryptionEnabled() {
        String uuid = UUID.randomUUID().toString();
        UploadRequest request = new UploadRequest("desc", false, "a-password", false,
                "secret.txt", 1, 5L, "1.2.3.4", "JUnit",
                false, null, null, false);

        Upload saved = fileLifecycleService.saveFile(request, uuid);

        assertNotNull(saved);
        assertTrue(saved.encrypted, "encryption is enabled by default and a password was supplied");
        assertNotNull(saved.passwordHash);
        assertTrue(passwordEncoder.matches("a-password", saved.passwordHash));
    }

    @Test
    void removeFileFromDatabaseSoftDeletesAndIsIdempotent() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        assertTrue(fileLifecycleService.removeFileFromDatabase(file.uuid));
        assertTrue(uploadRepository.findByUUID(file.uuid).orElseThrow().deleted);

        // Second call on an already-deleted record is a no-op success, not a failure.
        assertTrue(fileLifecycleService.removeFileFromDatabase(file.uuid));
    }

    @Test
    void removeFileFromDatabaseReturnsFalseForUnknownUuid() {
        assertFalse(fileLifecycleService.removeFileFromDatabase("no-such-uuid"));
    }

    @Test
    void removeFileFromDatabaseWithRequesterInfoLogsDeletionAndPurgesShareTokens() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        UploadShareLink token = new UploadShareLink(UUID.randomUUID().toString().substring(0, 8), file, null, null);
        shareTokenRepository.save(token);

        boolean result = fileLifecycleService.removeFileFromDatabase(file.uuid, "9.9.9.9", "JUnit-UA");

        assertTrue(result);
        assertEquals(1, activityLogRepository.countByFileAndType(file.uuid, EventType.DELETION));
        List<UploadShareLink> remaining = shareTokenRepository.findAllByFile(uploadRepository.findByUUID(file.uuid).orElseThrow());
        assertTrue(remaining.isEmpty(), "all share tokens must be purged when the file is soft-deleted");
    }

    @Test
    void extendFileResetsUploadDateToTodayAndLogsRenewal() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        file.uploadDate = LocalDate.now().minusDays(20);
        fileRepository.save(file);

        // A no-password file has no credential to check ownership against -- extend now
        // requires an admin session for it, matching the delete/edit precedent.
        fileLifecycleService.extendFile(file.uuid, requestWithAdminSession());

        Upload reloaded = uploadRepository.findByUUID(file.uuid).orElseThrow();
        assertEquals(LocalDate.now(), reloaded.uploadDate);
        assertEquals(1, activityLogRepository.countByFileAndType(file.uuid, EventType.RENEWAL));
    }

    /**
     * IDOR regression: a no-password upload has no credential to check a session token
     * against, so extend/keep-indefinitely/toggle-hidden now require an admin session for it
     * -- matching the precedent already set for delete and paste-edit.
     */
    @Test
    void extendFileAnonymousOnPlainFileIsDenied() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        LocalDate original = LocalDate.now().minusDays(20);
        file.uploadDate = original;
        fileRepository.save(file);

        fileLifecycleService.extendFile(file.uuid, new MockHttpServletRequest());

        assertEquals(original, uploadRepository.findByUUID(file.uuid).orElseThrow().uploadDate);
    }

    @Test
    void toggleHiddenFlipsFlagWhenNotAdminGated() {
        // "Not admin gated" refers to isHideFromListAdminOnly=false (the default) -- an
        // admin session is still required here regardless, since the file has no password
        // and therefore no other credential to prove ownership with.
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        assertFalse(file.hidden);
        MockHttpServletRequest admin = requestWithAdminSession();

        Upload afterFirst = fileLifecycleService.toggleHidden(file.uuid, admin);
        assertTrue(afterFirst.hidden);

        Upload afterSecond = fileLifecycleService.toggleHidden(file.uuid, admin);
        assertFalse(afterSecond.hidden);
    }

    @Test
    void toggleHiddenAnonymousOnPlainFileIsDenied() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        assertFalse(file.hidden);

        Upload result = fileLifecycleService.toggleHidden(file.uuid, new MockHttpServletRequest());

        assertNotNull(result);
        assertFalse(result.hidden);
        assertFalse(uploadRepository.findByUUID(file.uuid).orElseThrow().hidden);
    }

    @Test
    void toggleHiddenReturnsNullForUnknownUuid() {
        assertNull(fileLifecycleService.toggleHidden("no-such-uuid", new MockHttpServletRequest()));
    }

    @Test
    void toggleHiddenIsBlockedForNonAdminWhenAdminOnlySettingEnabled() {
        setHideFromListAdminOnly(true);
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        Upload result = fileLifecycleService.toggleHidden(file.uuid, new MockHttpServletRequest());

        assertNotNull(result);
        assertFalse(result.hidden, "toggle must be blocked (unchanged) without an admin session");
        assertFalse(uploadRepository.findByUUID(file.uuid).orElseThrow().hidden);
    }

    @Test
    void toggleHiddenIsAllowedForAdminWhenAdminOnlySettingEnabled() {
        setHideFromListAdminOnly(true);
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        Upload result = fileLifecycleService.toggleHidden(file.uuid, requestWithAdminSession());

        assertTrue(result.hidden);
    }

    @Test
    void updateKeepIndefinitelyResetsUploadDateWhenDisabling() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        file.keepIndefinitely = true;
        file.uploadDate = LocalDate.now().minusDays(50);
        fileRepository.save(file);

        Upload result = fileLifecycleService.updateKeepIndefinitely(file.uuid, false, requestWithAdminSession());

        assertNotNull(result);
        assertFalse(result.keepIndefinitely);
        assertEquals(LocalDate.now(), result.uploadDate, "disabling keepIndefinitely should give the file a fresh retention window");
    }

    @Test
    void updateKeepIndefinitelyAnonymousOnPlainFileIsDenied() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        Upload result = fileLifecycleService.updateKeepIndefinitely(file.uuid, true, new MockHttpServletRequest());

        assertNotNull(result);
        assertFalse(result.keepIndefinitely);
        assertFalse(uploadRepository.findByUUID(file.uuid).orElseThrow().keepIndefinitely);
    }

    @Test
    void updateKeepIndefinitelyIsBlockedForNonAdminWhenAdminOnlySettingEnabled() {
        setKeepIndefinitelyAdminOnly(true);
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        Upload result = fileLifecycleService.updateKeepIndefinitely(file.uuid, true, new MockHttpServletRequest());

        assertFalse(result.keepIndefinitely, "must be blocked without an admin session");
    }

    @Test
    void updateKeepIndefinitelyIsAllowedForAdminWhenAdminOnlySettingEnabled() {
        setKeepIndefinitelyAdminOnly(true);
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        Upload result = fileLifecycleService.updateKeepIndefinitely(file.uuid, true, requestWithAdminSession());

        assertTrue(result.keepIndefinitely);
    }

    @Test
    void generateShareTokenReusesExistingUnlimitedToken() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        UploadShareLink first = fileLifecycleService.generateShareToken(file.uuid, null, null);
        UploadShareLink second = fileLifecycleService.generateShareToken(file.uuid, null, null);

        assertEquals(first.getId(), second.getId(), "an unlimited token should be reused, not duplicated");
        assertEquals(1, shareTokenRepository.findAllByFile(uploadRepository.findByUUID(file.uuid).orElseThrow()).size());
    }

    @Test
    void generateShareTokenCreatesDistinctTokensWhenLimited() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        UploadShareLink first = fileLifecycleService.generateShareToken(file.uuid, null, 5);
        UploadShareLink second = fileLifecycleService.generateShareToken(file.uuid, null, 3);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, shareTokenRepository.findAllByFile(uploadRepository.findByUUID(file.uuid).orElseThrow()).size());
    }

    @Test
    void generateShareTokenThrowsForUnknownFile() {
        assertThrows(IllegalArgumentException.class,
                () -> fileLifecycleService.generateShareToken("no-such-uuid", null, null));
    }

    @Test
    void generateShareTokenForPlainFileHasNoShareKey() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());

        ShortLinkResult result = fileLifecycleService.generateShareToken(file.uuid, null, (String) null, null);

        assertNotNull(result.link());
        assertNull(result.shareKey(), "non-encrypted files must not get a per-share key");
    }

    @Test
    void generateShareTokenForEncryptedFileProducesReadySidecar() throws Exception {
        String uuid = UUID.randomUUID().toString();
        StoredFile file = new StoredFile();
        file.uuid = uuid;
        file.name = "secret.bin";
        file.size = 11;
        file.encrypted = true;
        file.passwordHash = passwordEncoder.encode("file-password-1");
        fileRepository.save(file);

        try (var out = encryptionService.getEncryptedOutputStream(storageService.getOutputStream(uuid), "file-password-1")) {
            out.write("hello world".getBytes());
        }

        String sessionToken = UUID.randomUUID().toString();
        sessionService.addFileSessionToken(sessionToken, "file-password-1", uuid);

        ShortLinkResult result = fileLifecycleService.generateShareToken(uuid, null, sessionToken, null);

        assertNotNull(result.shareKey(), "encrypted files must get a per-share key embedded in the share URL");
        assertNotNull(result.link().shareKeyHash);

        String sidecarKey = uuid + "-share-" + result.link().code;
        long deadline = System.currentTimeMillis() + 5000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            ready = shareTokenRepository.findUploadLinkById(result.link().getId()).map(t -> t.sidecarReady).orElse(false);
            if (ready) break;
            Thread.sleep(50);
        }
        assertTrue(ready, "background sidecar encryption should complete within 5s for an 11-byte file");
        assertTrue(storageService.exists(sidecarKey));
    }

    @Test
    void revokeShareTokenDeletesRowAndLogsRevoke() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        UploadShareLink token = fileLifecycleService.generateShareToken(file.uuid, null, null);

        fileLifecycleService.revokeShareToken(token.getId(), new MockHttpServletRequest());

        assertTrue(shareTokenRepository.findById(token.getId()).isEmpty());
        assertEquals(1, activityLogRepository.countByFileAndType(file.uuid, EventType.SHARE_REVOKE));
    }

    @Test
    void revokeShareTokenOfUnknownIdIsANoOp() {
        assertDoesNotThrow(() -> fileLifecycleService.revokeShareToken(-999L, new MockHttpServletRequest()));
    }

    @Test
    void mutationsEvictThePublicFilesCache() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        String cacheKey = "page:0:size:10:q:";

        fileQueryService.getVisibleFiles(PageRequest.of(0, 10), null);
        Cache publicFiles = cacheManager.getCache("publicFiles");
        assertNotNull(publicFiles);
        assertNotNull(publicFiles.get(cacheKey), "precondition: the cache should be populated after the read");

        fileLifecycleService.extendFile(file.uuid, requestWithAdminSession());

        assertNull(publicFiles.get(cacheKey), "extendFile must evict the publicFiles cache");
    }

    @Test
    void toggleHiddenEvictsAdminFilesAndAnalyticsCaches() {
        StoredFile file = persistPlainFile(UUID.randomUUID().toString());
        cacheManager.getCache("adminFiles").put("probe-key", "stale-value");
        cacheManager.getCache("analytics").put("probe-key", "stale-value");

        fileLifecycleService.toggleHidden(file.uuid, requestWithAdminSession());

        assertNull(cacheManager.getCache("adminFiles").get("probe-key"));
        assertNull(cacheManager.getCache("analytics").get("probe-key"));
    }

    private void setHideFromListAdminOnly(boolean value) {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setHideFromListAdminOnly(value);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
    }

    private void setKeepIndefinitelyAdminOnly(boolean value) {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setKeepIndefinitelyAdminOnly(value);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
    }
}
