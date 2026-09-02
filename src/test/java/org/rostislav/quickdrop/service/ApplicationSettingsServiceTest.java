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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

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

        // Only correct if @CacheEvict actually fired -- otherwise this returns the stale cached value read above.
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

    // validateLogoFile() throws before updateApplicationSettings() touches disk, so these rejections need no @TempDir.

    @Test
    void updateApplicationSettings_rejectsSvgDisguisedWithPngContentType() {
        // The attack this guards against: an SVG (which can carry <script>) uploaded with a spoofed image/png content type and .png filename, defeating MIME/extension checks alone.
        MockMultipartFile svgAsPng = new MockMultipartFile(
                "appLogo", "logo.png", "image/png",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());

        applicationSettingsService.updateApplicationSettings(vm, null, svgAsPng, false);

        assertNull(applicationSettingsService.getApplicationSettings().getLogoFileName(),
                "SVG content sniffed via magic bytes must be rejected even with a spoofed PNG content type");
    }

    @Test
    void updateApplicationSettings_rejectsDisallowedContentType() {
        MockMultipartFile svg = new MockMultipartFile(
                "appLogo", "logo.svg", "image/svg+xml", "<svg></svg>".getBytes(StandardCharsets.UTF_8));
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());

        applicationSettingsService.updateApplicationSettings(vm, null, svg, false);

        assertNull(applicationSettingsService.getApplicationSettings().getLogoFileName());
    }

    @Test
    void updateApplicationSettings_rejectsSvgExtensionEvenWithAllowedContentType() {
        // Extension check runs independently of the content-type check above.
        MockMultipartFile file = new MockMultipartFile(
                "appLogo", "logo.svg", "image/png", "not actually svg content".getBytes(StandardCharsets.UTF_8));
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());

        applicationSettingsService.updateApplicationSettings(vm, null, file, false);

        assertNull(applicationSettingsService.getApplicationSettings().getLogoFileName());
    }

    @Test
    void updateApplicationSettings_rejectsXmlDoctypeInBody() {
        MockMultipartFile file = new MockMultipartFile(
                "appLogo", "logo.png", "image/png",
                "<?xml version=\"1.0\"?><!DOCTYPE html><html></html>".getBytes(StandardCharsets.UTF_8));
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());

        applicationSettingsService.updateApplicationSettings(vm, null, file, false);

        assertNull(applicationSettingsService.getApplicationSettings().getLogoFileName());
    }

    @Test
    void updateApplicationSettings_rejectsFileOverTwoMegabyteLimit() {
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("appLogo", "logo.png", "image/png", oversized);
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());

        applicationSettingsService.updateApplicationSettings(vm, null, file, false);

        assertNull(applicationSettingsService.getApplicationSettings().getLogoFileName());
    }

    @Test
    void updateApplicationSettings_findingRejectedLogoAlsoDiscardsUnrelatedBundledChanges() {
        // FINDING, not a regression guard: a rejected logo's early return skips repository.save(), so an unrelated bundled change (e.g. maxFileSize) is silently discarded too. Documents current behavior so a future fix must consciously update this test.
        long originalMax = applicationSettingsService.getMaxFileSize();
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setMaxFileSize(originalMax + 12_345L);
        MockMultipartFile badLogo = new MockMultipartFile(
                "appLogo", "logo.svg", "image/svg+xml", "<svg></svg>".getBytes(StandardCharsets.UTF_8));

        applicationSettingsService.updateApplicationSettings(vm, null, badLogo, false);

        assertEquals(originalMax, applicationSettingsService.getMaxFileSize(),
                "current behavior: the unrelated maxFileSize change is discarded along with the rejected logo");
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
            // Leaving this enabled would gate every route behind Spring Security auth for every other test class sharing this context; no @Order pins run-last here, so self-clean unconditionally.
            ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            cleanup.setAppPasswordEnabled(false);
            applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
        }
    }

    @Test
    void omittingAppPasswordFlagDisablesAppPassword() {
        ApplicationSettingsViewModel enableVm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        enableVm.setAppPasswordEnabled(true);
        applicationSettingsService.updateApplicationSettings(enableVm, "temp-pw", null, false);
        assertTrue(applicationSettingsService.isAppPasswordEnabled());

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
     * Unordered relative to other tests sharing this context, so leaving a disabled flag behind
     * would non-deterministically break upload-dependent tests elsewhere in the suite.
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
            // Every event type defaults to notifications-enabled, so a leftover live webhook would make a later test elsewhere in this shared-context suite fire a real network POST.
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
            // s3SecretKey is only ever overwritten on a non-blank value (by design), so there's no public API to clear it -- write via the repository directly and evict the cache manually, since a leftover secret key was observed to throw a raw NPE from the AWS SDK in an unrelated test.
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

    private void resetReputationState() {
        ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
        entity.setReputationPhishingArmyEnabled(false);
        entity.setPhishingArmyTermsAcceptedAt(null);
        applicationSettingsRepository.save(entity);
        var cache = cacheManager.getCache("applicationSettings");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void acceptReputationProviderTermsEnablesTheProviderAndStampsATimestamp() {
        try {
            boolean accepted = applicationSettingsService.acceptReputationProviderTerms("phishing_army");

            assertTrue(accepted);
            ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
            assertTrue(entity.isReputationPhishingArmyEnabled());
            assertNotNull(entity.getPhishingArmyTermsAcceptedAt());
        } finally {
            resetReputationState();
        }
    }

    @Test
    void acceptReputationProviderTermsReturnsFalseForAnUnknownProvider() {
        assertFalse(applicationSettingsService.acceptReputationProviderTerms("not-a-real-provider"));
    }

    @Test
    void settingsSaveCannotEnableAProviderWithoutPriorAcceptance() {
        // Simulates a raw POST bypassing the licence modal -- a bare "enabled=true" must not be trusted without a recorded acceptance.
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setReputationPhishingArmyEnabled(true);

        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        assertFalse(applicationSettingsService.isReputationPhishingArmyEnabled(),
                "enabling via the general settings save must be ignored without a prior acceptReputationProviderTerms call");
    }

    @Test
    void disablingAProviderViaSettingsSaveClearsItsAcceptedTimestampSoReEnablingReprompts() {
        try {
            applicationSettingsService.acceptReputationProviderTerms("phishing_army");
            assertNotNull(applicationSettingsRepository.findById(1L).orElseThrow().getPhishingArmyTermsAcceptedAt());

            ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            vm.setReputationPhishingArmyEnabled(false);
            applicationSettingsService.updateApplicationSettings(vm, null, null, false);

            ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
            assertFalse(entity.isReputationPhishingArmyEnabled());
            assertNull(entity.getPhishingArmyTermsAcceptedAt(),
                    "disabling must clear the acceptance timestamp so re-enabling later re-prompts for the licence");
        } finally {
            resetReputationState();
        }
    }
}
