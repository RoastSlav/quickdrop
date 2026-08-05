package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.rostislav.quickdrop.storage.StorageBackend;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.bcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the singleton settings-row invariant, the {@code updateApplicationSettings}
 * mutation/cache-eviction path, and admin-password hashing.
 *
 * <p>Uses the real Spring context ({@link QuickdropIntegrationTest}) because
 * {@code ApplicationSettingsService} routes its own getters through a {@code @Lazy}
 * self-injected proxy for {@code @Cacheable} interception -- a manually-constructed
 * instance would bypass caching entirely and not exercise what we're testing.
 *
 * <p>The settings row is a process-wide singleton and, per application-test.properties,
 * this class's Spring context (and its SQLite DB) is cached and reused across every
 * other test class with an identical configuration -- so a mutation made by one test
 * method is visible to every test method that runs after it, in this class or any
 * other. Two things follow: (1) tests whose "before" state matters (e.g. "no app
 * password hash exists yet") are pinned with {@code @Order} so they run before any
 * test that would set that state; (2) tests that flip a flag to something abnormal
 * (S3 credentials, the Discord webhook) restore it afterwards so they don't leak into
 * unrelated tests running later in the same process (e.g. real Discord webhook POSTs
 * firing from an unrelated file-upload test elsewhere in the suite).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationSettingsServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private ApplicationSettingsService applicationSettingsService;

    @Autowired
    private ApplicationSettingsRepository applicationSettingsRepository;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void settingsRowIsSeededExactlyOnceWithIdOne() {
        assertEquals(1, applicationSettingsRepository.count());
        ApplicationSettingsEntity settings = applicationSettingsService.getApplicationSettings();
        assertEquals(1L, settings.getId());
    }

    @Test
    void repeatedReadsNeverCreateASecondRow() {
        for (int i = 0; i < 5; i++) {
            applicationSettingsService.getApplicationSettings();
        }
        assertEquals(1, applicationSettingsRepository.count());
    }

    @Test
    void updateApplicationSettingsPersistsChangesAndEvictsCache() {
        ApplicationSettingsEntity before = applicationSettingsService.getApplicationSettings();
        long originalMax = before.getMaxFileSize();
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(before);
        vm.setMaxFileSize(originalMax + 999_999L);

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        // getMaxFileSize() is only correct post-update if the @CacheEvict actually fired --
        // otherwise this would still return the stale cached value read above.
        assertEquals(originalMax + 999_999L, applicationSettingsService.getMaxFileSize());
        assertEquals(1, applicationSettingsRepository.count(), "update must never insert a second settings row");
    }

    @Test
    void updateApplicationSettingsNeverDuplicatesTheSingletonRow() {
        ApplicationSettingsEntity entity = applicationSettingsService.getApplicationSettings();
        for (int i = 0; i < 3; i++) {
            ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            vm.setMaxFileLifeTime(10 + i);
            applicationSettingsService.updateApplicationSettings(vm, null, null, false);
        }
        assertEquals(1, applicationSettingsRepository.count());
        assertEquals(12, applicationSettingsService.getMaxFileLifeTime());
    }

    @Test
    void setAdminPasswordStoresABCryptHash() {
        applicationSettingsService.setAdminPassword("s3cr3t-admin-pw");

        String hash = applicationSettingsService.getAdminPasswordHash();
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2"), "admin password must be BCrypt-hashed, got: " + hash);
        assertTrue(BCrypt.checkpw("s3cr3t-admin-pw", hash));
        assertFalse(BCrypt.checkpw("wrong-password", hash));
        assertTrue(applicationSettingsService.isAdminPasswordSet());
    }

    @Test
    void setAdminPasswordRejectsBlankOrNull() {
        assertThrows(IllegalArgumentException.class, () -> applicationSettingsService.setAdminPassword(""));
        assertThrows(IllegalArgumentException.class, () -> applicationSettingsService.setAdminPassword("   "));
        assertThrows(IllegalArgumentException.class, () -> applicationSettingsService.setAdminPassword(null));
    }

    @Test
    @Order(1) // must run before any test in this class sets an app password hash -- see class Javadoc
    void enablingAppPasswordWithoutAnyHashSetThrows() {
        ApplicationSettingsEntity entity = applicationSettingsService.getApplicationSettings();
        assertTrue(entity.getAppPasswordHash() == null || entity.getAppPasswordHash().isBlank(),
                "precondition: no app password hash set yet");
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(entity);
        vm.setAppPasswordEnabled(true);

        assertThrows(IllegalArgumentException.class,
                () -> applicationSettingsService.updateApplicationSettings(vm, null, null, false));
    }

    @Test
    void providingAppPasswordEnablesItAndStoresBCryptHash() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setAppPasswordEnabled(true);

        applicationSettingsService.updateApplicationSettings(vm, "app-access-pw", null, false);

        try {
            assertTrue(applicationSettingsService.isAppPasswordEnabled());
            String hash = applicationSettingsService.getAppPasswordHash();
            assertTrue(hash.startsWith("$2"));
            assertTrue(BCrypt.checkpw("app-access-pw", hash));
        } finally {
            // Leaving the app password enabled would gate every route behind Spring Security
            // authentication for every other test class sharing this context (no @Order
            // relative to omittingAppPasswordFlagDisablesAppPassword pins execution order,
            // so this must self-clean regardless of which runs last on a given JVM). Restore
            // a safe state regardless of assertion outcome, matching the Discord webhook
            // test's cleanup pattern above.
            ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            cleanup.setAppPasswordEnabled(false);
            applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
        }
    }

    @Test
    void omittingAppPasswordFlagDisablesAppPassword() {
        // First enable it...
        ApplicationSettingsViewModel enableVm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        enableVm.setAppPasswordEnabled(true);
        applicationSettingsService.updateApplicationSettings(enableVm, "temp-pw", null, false);
        assertTrue(applicationSettingsService.isAppPasswordEnabled());

        // ...then a save with the flag off (and no new password) must disable it again.
        ApplicationSettingsViewModel disableVm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        disableVm.setAppPasswordEnabled(false);
        applicationSettingsService.updateApplicationSettings(disableVm, null, null, false);

        assertFalse(applicationSettingsService.isAppPasswordEnabled());
    }

    @Test
    void defaultHomePageFallsBackWhenRequestedPageIsDisabled() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setDefaultHomePage("upload");
        vm.setUploadEnabled(false);
        vm.setFileListPageEnabled(true);
        vm.setPastebinEnabled(true);

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        try {
            assertEquals("list", applicationSettingsService.getDefaultHomePage(),
                    "upload is disabled, so the home page should fall through to the next available page");
        } finally {
            restoreUploadDefaults();
        }
    }

    @Test
    void defaultHomePageFallsAllTheWayToNoneWhenEverythingIsDisabled() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setDefaultHomePage("upload");
        vm.setUploadEnabled(false);
        vm.setFileListPageEnabled(false);
        vm.setPastebinEnabled(false);

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        try {
            assertEquals("none", applicationSettingsService.getApplicationSettings().getDefaultHomePage());
        } finally {
            restoreUploadDefaults();
        }
    }

    @Test
    void uploadAdminOnlyIsForcedFalseWhenUploadIsDisabled() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setUploadEnabled(false);
        vm.setUploadAdminOnly(true);

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        try {
            assertFalse(applicationSettingsService.getApplicationSettings().isUploadAdminOnly(),
                    "uploadAdminOnly must not stick when upload itself is disabled");
        } finally {
            restoreUploadDefaults();
        }
    }

    /**
     * Restores upload/file-list/pastebin/home-page settings to their fresh-install defaults.
     * None of the three tests above have an {@code @Order} relative to each other or to any
     * test in another class sharing this context, so leaving {@code uploadEnabled=false} (or
     * similar) behind would silently break upload-dependent tests elsewhere in the suite,
     * non-deterministically depending on JVM method-reflection order -- the same class of bug
     * fixed for {@code providingAppPasswordEnablesItAndStoresBCryptHash} above.
     */
    private void restoreUploadDefaults() {
        ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        cleanup.setUploadEnabled(true);
        cleanup.setUploadAdminOnly(false);
        cleanup.setFileListPageEnabled(true);
        cleanup.setPastebinEnabled(true);
        cleanup.setDefaultHomePage("upload");
        applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
    }

    @Test
    void discordWebhookUrlIsRejectedWhenNotADiscordDomain() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setDiscordWebhookEnabled(true);
        vm.setDiscordWebhookUrl("https://evil.example.com/webhook");

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        assertEquals("", applicationSettingsService.getDiscordWebhookUrl());
        assertFalse(applicationSettingsService.isDiscordWebhookEnabled(),
                "an SSRF-guard-rejected webhook URL must also force the enabled flag off");
    }

    @Test
    void discordWebhookUrlIsAcceptedForRealDiscordDomain() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setDiscordWebhookEnabled(true);
        vm.setDiscordWebhookUrl("https://discord.com/api/webhooks/123/abc");

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
        try {
            assertEquals("https://discord.com/api/webhooks/123/abc", applicationSettingsService.getDiscordWebhookUrl());
            assertTrue(applicationSettingsService.isDiscordWebhookEnabled());
        } finally {
            // Every event type defaults to notifications-enabled (see ApplicationSettingsEntity),
            // so leaving a live Discord webhook configured would make any later
            // upload/download/paste test elsewhere in this shared-context suite fire a real
            // network POST. Restore a safe state regardless of assertion outcome.
            ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            cleanup.setDiscordWebhookEnabled(false);
            cleanup.setDiscordWebhookUrl("");
            applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
        }
    }

    @Test
    void isBackendConfiguredIsAlwaysTrueForLocal() {
        assertTrue(applicationSettingsService.isBackendConfigured(StorageBackend.LOCAL));
    }

    @Test
    @Order(2) // must run before isBackendConfiguredIsTrueForS3OnceCredentialsAreSaved persists S3 creds
    void isBackendConfiguredIsFalseForS3WithoutCredentials() {
        assertFalse(applicationSettingsService.isBackendConfigured(StorageBackend.S3));
    }

    @Test
    void isBackendConfiguredIsTrueForS3OnceCredentialsAreSaved() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setS3Bucket("my-bucket");
        vm.setS3AccessKey("AKIAEXAMPLE");
        vm.setS3SecretKey("supersecret");

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
        try {
            assertTrue(applicationSettingsService.isBackendConfigured(StorageBackend.S3));
        } finally {
            // updateApplicationSettings() only ever overwrites s3SecretKey on a non-blank
            // value (by design, so a settings save never accidentally wipes a saved
            // secret) -- there is no public-API way to clear it back to blank. Go
            // straight to the repository so this test's leftover S3 credentials don't
            // leak into any other test in this shared-context suite (an inconsistent
            // partial config -- blank access key but a leftover secret key -- was
            // observed to throw a raw NPE from the AWS SDK in an unrelated test
            // elsewhere in the run), then evict the settings cache manually since a
            // direct repository write bypasses @CacheEvict.
            ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
            entity.setS3Bucket("");
            entity.setS3AccessKey("");
            entity.setS3SecretKey("");
            applicationSettingsRepository.save(entity);
            var cache = cacheManager.getCache("applicationSettings");
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
