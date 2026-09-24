package org.rostislav.quickdrop.service;

import jakarta.annotation.PostConstruct;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.EventCategory;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.rostislav.quickdrop.storage.*;
import org.rostislav.quickdrop.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

/**
 * Provides access to the single-row application settings record and handles
 * runtime updates to those settings.
 *
 * <p>The settings entity is cached under the {@code applicationSettings} cache.
 * Getter methods route calls through {@code self}, a {@link Lazy @Lazy}-injected
 * self-reference, to ensure cache interception is applied.
 *
 * <p>On startup ({@link #initSettings()}) the settings row is created with
 * sensible defaults if it does not yet exist. After the application context is
 * fully started ({@link #onApplicationReady()}) the cleanup schedule is
 * initialised with the persisted cron expression and max file lifetime.
 *
 * <p>{@link #updateApplicationSettings} evicts the cache, persists all changed fields, and
 * publishes a {@link SettingsChangedEvent} so anything that can't simply read this service
 * live (a cached SDK client, a running scheduled task) can react.
 */
@Service
public class ApplicationSettingsService {
    /**
     * Directory the application log is written to when the admin has not configured one.
     * Matches the {@code log/} directory declared as a Docker volume by the Dockerfile and
     * docker-compose, and is read at startup by
     * {@link org.rostislav.quickdrop.config.LogStoragePathEnvironmentPostProcessor}.
     */
    public static final String DEFAULT_LOG_STORAGE_PATH = "log";

    private static final String UPLOAD_PAGE = "upload";
    private static final String PASTE_PAGE = "paste";
    private static final String DEFAULT_APP_NAME = "QuickDrop";
    private static final String DEFAULT_FAVICON_PATH = "/images/favicon.png";
    private static final String INTERSTITIAL_NON_ADMIN = "NON_ADMIN";
    private static final String DAILY_330AM_CRON = "0 30 3 * * *";

    private static final Logger logger = LoggerFactory.getLogger(ApplicationSettingsService.class);

    private final ApplicationSettingsRepository applicationSettingsRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Self-reference for routing calls through the Spring AOP proxy.
     */
    @Lazy
    @Autowired
    private ApplicationSettingsService self;

    /** Only for the one-off initial schedule in {@link #onApplicationReady()}; routine
     *  rescheduling is handled by {@link ScheduleService}'s own event listener. */
    @Lazy
    @Autowired
    private ScheduleService scheduleService;

    /** Only for the one-off initial schedule in {@link #onApplicationReady()}; routine
     *  rescheduling is handled by {@link BackupService}'s own event listener. */
    @Lazy
    @Autowired
    private BackupService backupService;

    /** Used only by the admin panel's "Test Connection" button -- settings-save
     *  propagation goes through each service's own {@link SettingsChangedEvent} listener. */
    @Lazy
    @Autowired
    private S3StorageService s3StorageService;

    @Lazy
    @Autowired
    private AzureBlobStorageService azureStorageService;

    @Lazy
    @Autowired
    private SftpStorageService sftpStorageService;

    @Lazy
    @Autowired
    private WebDavStorageService webDavStorageService;

    public ApplicationSettingsService(ApplicationSettingsRepository applicationSettingsRepository,
                                      ApplicationEventPublisher eventPublisher) {
        this.applicationSettingsRepository = applicationSettingsRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Ensures the settings row (ID 1) exists with safe defaults.
     */
    @PostConstruct
    public void initSettings() {
        ApplicationSettingsEntity settings = applicationSettingsRepository.findById(1L).orElseGet(() -> {
            ApplicationSettingsEntity defaults = new ApplicationSettingsEntity();
            defaults.setMaxFileSize(1024L * 1024L * 1024L);
            defaults.setMaxFileLifeTime(30L);
            defaults.setFileStoragePath("files");
            defaults.setLogStoragePath(DEFAULT_LOG_STORAGE_PATH);
            defaults.setFileDeletionCron("0 0 2 * * *");
            defaults.setAppPasswordEnabled(false);
            defaults.setAppPasswordHash("");
            defaults.setAdminPasswordHash("");
            defaults.setSessionLifetime(30);
            defaults.setFileListPageEnabled(true);
            defaults.setAdminDashboardButtonEnabled(true);
            defaults.setEncryptionEnabled(true);
            defaults.setUploadPasswordEnabled(true);
            defaults.setPreviewEnabled(true);
            defaults.setMetadataStrippingEnabled(false);
            defaults.setMaxPreviewSizeBytes(5L * 1024L * 1024L);
            defaults.setDefaultHomePage(UPLOAD_PAGE);
            defaults.setKeepIndefinitelyAdminOnly(false);
            defaults.setHideFromListAdminOnly(false);
            defaults.setDiscordWebhookEnabled(false);
            defaults.setDiscordWebhookUrl("");
            defaults.setEmailNotificationsEnabled(false);
            defaults.setEmailFrom("");
            defaults.setEmailTo("");
            defaults.setSmtpHost("");
            defaults.setSmtpPort(587);
            defaults.setSmtpUsername("");
            defaults.setSmtpPassword("");
            defaults.setSmtpUseTls(true);
            defaults.setSmtpUseSsl(false);
            defaults.setNotificationBatchEnabled(false);
            defaults.setNotificationBatchMinutes(5);
            defaults.setSimplifiedShareLinks(false);
            defaults.setShareLinksEnabled(true);
            defaults.setUploadEnabled(true);
            defaults.setUploadAdminOnly(false);
            defaults.setPastebinEnabled(true);
            defaults.setShortenerEnabled(true);
            defaults.setShortenerAdminOnly(false);
            defaults.setShortenerCodeLength(5);
            defaults.setShareTokenLength(8);
            defaults.setShortenerCustomAliasEnabled(true);
            defaults.setShortenerCustomAliasAdminOnly(true);
            defaults.setShortenerInterstitialMode(INTERSTITIAL_NON_ADMIN);
            defaults.setShortenerDomainRuleMode("OFF");
            defaults.setShortenerDomainRules("");
            defaults.setTrustedProxyEnabled(false);
            defaults.setForceSecureCookiesEnabled(false);
            defaults.setShortenerClickLoggingEnabled(true);
            defaults.setReputationCheckEnabled(false);
            defaults.setReputationPhishingArmyEnabled(false);
            defaults.setReputationUrlhausEnabled(false);
            defaults.setReputationSafeBrowsingEnabled(false);
            defaults.setReputationFailClosed(false);
            defaults.setReputationFeedCron("0 0 4 * * *");
            defaults.setActivityRetentionEnabled(false);
            defaults.setActivityRetentionCron(DAILY_330AM_CRON);
            defaults.setActivityRetentionFileDays(365);
            defaults.setActivityRetentionPasteDays(365);
            defaults.setActivityRetentionShareDays(365);
            defaults.setActivityRetentionShortlinkDays(365);
            defaults.setActivityRetentionAdminDays(365);
            defaults.setActivityRetentionSystemDays(365);
            defaults.setAppName(DEFAULT_APP_NAME);
            defaults.setLogoFileName(null);
            defaults.setDefaultLanguage("en");
            return applicationSettingsRepository.save(defaults);
        });

        boolean dirty = false;
        if (settings.getAppName() == null || settings.getAppName().isBlank()) {
            settings.setAppName(DEFAULT_APP_NAME);
            dirty = true;
        }
        if (settings.getMaxFileSize() == 0) {
            settings.setMaxFileSize(1024L * 1024L * 1024L);
            dirty = true;
        }
        if (settings.getMaxFileLifeTime() == 0) {
            settings.setMaxFileLifeTime(30L);
            dirty = true;
        }
        if (settings.getMaxPreviewSizeBytes() == 0) {
            settings.setMaxPreviewSizeBytes(5L * 1024L * 1024L);
            dirty = true;
        }
        if (settings.getSessionLifetime() == 0) {
            settings.setSessionLifetime(30);
            dirty = true;
        }
        if (settings.getFileDeletionCron() == null || settings.getFileDeletionCron().isBlank()) {
            settings.setFileDeletionCron("0 0 2 * * *");
            dirty = true;
        }
        if (settings.getDefaultHomePage() == null || settings.getDefaultHomePage().isBlank()) {
            settings.setDefaultHomePage(UPLOAD_PAGE);
            dirty = true;
        }
        if (settings.getDefaultLanguage() == null || settings.getDefaultLanguage().isBlank()) {
            settings.setDefaultLanguage("en");
            dirty = true;
        }
        if (settings.getLogStoragePath() == null || settings.getLogStoragePath().isBlank()) {
            settings.setLogStoragePath(DEFAULT_LOG_STORAGE_PATH);
            dirty = true;
        }
        if (settings.getFileStoragePath() == null || settings.getFileStoragePath().isBlank()) {
            settings.setFileStoragePath("files");
            dirty = true;
        }
        if (settings.getShortenerCodeLength() <= 0) {
            settings.setShortenerCodeLength(5);
            dirty = true;
        }
        // Backfills rows created before share_token_length existed. Those instances were
        // minting share tokens at shortener_code_length (default 5); leaving a 0/unset
        // value here would fall through to generateUniqueCode(0) and mint empty codes.
        if (settings.getShareTokenLength() <= 0) {
            settings.setShareTokenLength(8);
            dirty = true;
        }
        if (settings.getActivityRetentionCron() == null || settings.getActivityRetentionCron().isBlank()) {
            settings.setActivityRetentionCron(DAILY_330AM_CRON);
            dirty = true;
        }
        if (settings.getShortenerInterstitialMode() == null || settings.getShortenerInterstitialMode().isBlank()) {
            settings.setShortenerInterstitialMode(INTERSTITIAL_NON_ADMIN);
            dirty = true;
        }
        if (settings.getShortenerDomainRuleMode() == null || settings.getShortenerDomainRuleMode().isBlank()) {
            settings.setShortenerDomainRuleMode("OFF");
            dirty = true;
        }
        if (dirty) {
            applicationSettingsRepository.save(settings);
        }
    }

    /**
     * Fires the initial cleanup schedule once the application context is fully started,
     * using the persisted cron expression and max file lifetime.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ApplicationSettingsEntity settings = self.getApplicationSettings();
        scheduleService.updateSchedule(settings.getFileDeletionCron(), settings.getMaxFileLifeTime());
        backupService.updateSchedule(settings.getBackupCron(), settings.isBackupScheduleEnabled());
    }

    /**
     * Loads the settings row (ID 1), caching it until the cache is evicted.
     */
    @Cacheable("applicationSettings")
    public ApplicationSettingsEntity getApplicationSettings() {
        return applicationSettingsRepository.findById(1L).orElseThrow();
    }

    private static boolean isBlankOrNull(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Coerces the requested {@code defaultHomePage} value so that it always points at a
     * feature that is actually reachable by public visitors.
     *
     * <p>If the requested page is disabled or (for uploads) restricted to admins only,
     * the method falls through to the next available page in priority order:
     * upload → list → paste → none.
     */
    private String coerceDefaultHomePage(ApplicationSettingsViewModel settings) {
        String page = settings.getDefaultHomePage();
        if (page == null) return UPLOAD_PAGE;
        boolean uploadPublic = settings.isUploadEnabled() && !settings.isUploadAdminOnly();
        boolean listEnabled = settings.isFileListPageEnabled();
        boolean pasteEnabled = settings.isPastebinEnabled();
        switch (page.toLowerCase()) {
            case UPLOAD_PAGE:
                if (!uploadPublic) page = listEnabled ? "list" : pasteEnabled ? PASTE_PAGE : "none";
                break;
            case "list":
                if (!listEnabled) page = uploadPublic ? UPLOAD_PAGE : pasteEnabled ? PASTE_PAGE : "none";
                break;
            case PASTE_PAGE:
                if (!pasteEnabled) page = uploadPublic ? UPLOAD_PAGE : listEnabled ? "list" : "none";
                break;
        }
        return page;
    }

    /**
     * Persists all settings from the view-model, evicts the settings cache, and publishes a
     * {@link SettingsChangedEvent} so the cleanup schedule, storage backend clients, and
     * storage health check all pick up the change.
     *
     * <p>The SMTP password is only overwritten if a non-blank value is provided in the
     * view-model. When upload passwords are disabled, encryption is also disabled.
     * If {@code clearLogo} is {@code true} the stored logo filename is cleared;
     * otherwise a non-empty {@code logoFile} is saved to the {@code branding/}
     * directory and the filename is recorded.
     *
     * @param appPassword new plaintext app password, or {@code null}/{@code ""} to leave unchanged
     */
    // Two evictions, not one: beforeInvocation covers a mid-method failure before save()
    // runs; the post-invocation one closes a race where a concurrent read (e.g.
    // StorageHealthService's async recheck, triggered by the event published below) lands
    // between the beforeInvocation eviction and save(), repopulating the cache with the
    // stale pre-update row.
    @Caching(evict = {
            @CacheEvict(value = "applicationSettings", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "applicationSettings", allEntries = true)
    })
    public void updateApplicationSettings(ApplicationSettingsViewModel settings, String appPassword, MultipartFile logoFile, boolean clearLogo) {
        ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
        entity.setMaxFileSize(settings.getMaxFileSize());
        entity.setMaxFileLifeTime(settings.getMaxFileLifeTime());
        entity.setFileStoragePath(settings.getFileStoragePath());
        entity.setLogStoragePath(settings.getLogStoragePath());
        entity.setFileDeletionCron(settings.getFileDeletionCron());
        entity.setSessionLifetime(settings.getSessionLifeTime());
        entity.setFileListPageEnabled(settings.isFileListPageEnabled());
        entity.setAdminDashboardButtonEnabled(settings.isAdminDashboardButtonEnabled());
        boolean uploadPasswordEnabled = settings.isUploadPasswordEnabled();
        entity.setUploadPasswordEnabled(uploadPasswordEnabled);
        entity.setEncryptionEnabled(uploadPasswordEnabled && settings.isEncryptionEnabled());
        entity.setPreviewEnabled(settings.isPreviewEnabled());
        entity.setMetadataStrippingEnabled(settings.isMetadataStrippingEnabled());
        entity.setMaxPreviewSizeBytes(settings.getMaxPreviewSizeBytes());
        entity.setDefaultHomePage(coerceDefaultHomePage(settings));
        entity.setKeepIndefinitelyAdminOnly(settings.isKeepIndefinitelyAdminOnly());
        entity.setHideFromListAdminOnly(settings.isHideFromListAdminOnly());
        boolean shareLinksEnabled = settings.isShareLinksEnabled();
        entity.setShareLinksEnabled(shareLinksEnabled);
        boolean uploadEnabled = settings.isUploadEnabled();
        entity.setUploadEnabled(uploadEnabled);
        entity.setUploadAdminOnly(uploadEnabled && settings.isUploadAdminOnly());
        // Only accept https://discord.com or https://discordapp.com — prevents SSRF via a
        // saved webhook URL.
        String webhookUrl = settings.getDiscordWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            try {
                java.net.URI uri = new java.net.URI(webhookUrl);
                String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
                boolean validDiscord = "https".equalsIgnoreCase(uri.getScheme()) &&
                        (host.equals("discord.com") || host.endsWith(".discord.com")
                                || host.equals("discordapp.com") || host.endsWith(".discordapp.com"));
                if (!validDiscord) {
                    logger.warn("Discord webhook URL rejected at save: must be https://discord.com or https://discordapp.com, got: {}", webhookUrl);
                    webhookUrl = "";
                    settings.setDiscordWebhookEnabled(false);
                }
            } catch (Exception e) {
                logger.warn("Discord webhook URL is malformed, clearing: {}", webhookUrl);
                webhookUrl = "";
                settings.setDiscordWebhookEnabled(false);
            }
        }
        entity.setDiscordWebhookEnabled(settings.isDiscordWebhookEnabled());
        entity.setDiscordWebhookUrl(webhookUrl);
        entity.setEmailNotificationsEnabled(settings.isEmailNotificationsEnabled());
        entity.setEmailFrom(settings.getEmailFrom());
        entity.setEmailTo(settings.getEmailTo());
        entity.setSmtpHost(settings.getSmtpHost());
        entity.setSmtpPort(settings.getSmtpPort());
        entity.setSmtpUsername(settings.getSmtpUsername());
        if (settings.getSmtpPassword() != null && !settings.getSmtpPassword().isBlank()) {
            entity.setSmtpPassword(settings.getSmtpPassword());
        }
        entity.setSmtpUseTls(settings.isSmtpUseTls());
        entity.setSmtpUseSsl(settings.isSmtpUseSsl());
        entity.setNotificationBatchEnabled(settings.isNotificationBatchEnabled());
        Integer existingBatchMinutes = entity.getNotificationBatchMinutes();
        Integer requestedBatchMinutes = settings.getNotificationBatchMinutes();
        if (requestedBatchMinutes != null) {
            entity.setNotificationBatchMinutes(requestedBatchMinutes);
        } else if (existingBatchMinutes != null) {
            entity.setNotificationBatchMinutes(existingBatchMinutes);
        }
        entity.setSimplifiedShareLinks(shareLinksEnabled && settings.isSimplifiedShareLinks());
        entity.setPastebinEnabled(settings.isPastebinEnabled());
        boolean shortenerEnabled = settings.isShortenerEnabled();
        entity.setShortenerEnabled(shortenerEnabled);
        entity.setShortenerAdminOnly(shortenerEnabled && settings.isShortenerAdminOnly());
        entity.setShortenerCodeLength(settings.getShortenerCodeLength() > 0 ? settings.getShortenerCodeLength() : 5);
        // Floored at 8, not just >0: this token gates the file's bytes, unlike the freely
        // adjustable shortener code length above (which only guards a destination URL).
        entity.setShareTokenLength(Math.max(settings.getShareTokenLength(), 8));
        boolean shortenerCustomAliasEnabled = shortenerEnabled && settings.isShortenerCustomAliasEnabled();
        entity.setShortenerCustomAliasEnabled(shortenerCustomAliasEnabled);
        entity.setShortenerCustomAliasAdminOnly(shortenerCustomAliasEnabled && settings.isShortenerCustomAliasAdminOnly());
        String interstitialMode = settings.getShortenerInterstitialMode();
        entity.setShortenerInterstitialMode(
                java.util.Set.of("ALWAYS", "NEVER", INTERSTITIAL_NON_ADMIN).contains(interstitialMode == null ? "" : interstitialMode)
                        ? interstitialMode : INTERSTITIAL_NON_ADMIN);
        String domainRuleMode = settings.getShortenerDomainRuleMode();
        entity.setShortenerDomainRuleMode(
                java.util.Set.of("OFF", "BLOCKLIST", "ALLOWLIST").contains(domainRuleMode == null ? "" : domainRuleMode)
                        ? domainRuleMode : "OFF");
        entity.setShortenerDomainRules(settings.getShortenerDomainRules() != null ? settings.getShortenerDomainRules() : "");
        entity.setTrustedProxyEnabled(settings.isTrustedProxyEnabled());
        entity.setForceSecureCookiesEnabled(settings.isForceSecureCookiesEnabled());
        entity.setShortenerClickLoggingEnabled(settings.isShortenerClickLoggingEnabled());
        entity.setReputationCheckEnabled(settings.isReputationCheckEnabled());
        entity.setReputationFailClosed(settings.isReputationFailClosed());
        String reputationFeedCron = settings.getReputationFeedCron();
        entity.setReputationFeedCron(reputationFeedCron != null && !reputationFeedCron.isBlank() ? reputationFeedCron : "0 0 4 * * *");
        entity.setActivityRetentionEnabled(settings.isActivityRetentionEnabled());
        String activityRetentionCron = settings.getActivityRetentionCron();
        entity.setActivityRetentionCron(activityRetentionCron != null && !activityRetentionCron.isBlank() ? activityRetentionCron : DAILY_330AM_CRON);
        // A negative would put the cutoff in the future and purge unexpired rows; 0 = forever.
        entity.setActivityRetentionFileDays(Math.max(settings.getActivityRetentionFileDays(), 0));
        entity.setActivityRetentionPasteDays(Math.max(settings.getActivityRetentionPasteDays(), 0));
        entity.setActivityRetentionShareDays(Math.max(settings.getActivityRetentionShareDays(), 0));
        entity.setActivityRetentionShortlinkDays(Math.max(settings.getActivityRetentionShortlinkDays(), 0));
        entity.setActivityRetentionAdminDays(Math.max(settings.getActivityRetentionAdminDays(), 0));
        entity.setActivityRetentionSystemDays(Math.max(settings.getActivityRetentionSystemDays(), 0));
        entity.setUrlhausAuthKey(settings.getUrlhausAuthKey());
        entity.setSafeBrowsingApiKey(settings.getSafeBrowsingApiKey());
        // Can only be (re-)enabled via #acceptReputationProviderTerms; disabling here also
        // clears the acceptance timestamp so re-enabling always re-prompts for the licence.
        entity.setReputationPhishingArmyEnabled(settings.isReputationPhishingArmyEnabled() && entity.getPhishingArmyTermsAcceptedAt() != null);
        if (!entity.isReputationPhishingArmyEnabled()) {
            entity.setPhishingArmyTermsAcceptedAt(null);
        }
        entity.setReputationUrlhausEnabled(settings.isReputationUrlhausEnabled() && entity.getUrlhausTermsAcceptedAt() != null);
        if (!entity.isReputationUrlhausEnabled()) {
            entity.setUrlhausTermsAcceptedAt(null);
        }
        entity.setReputationSafeBrowsingEnabled(settings.isReputationSafeBrowsingEnabled() && entity.getSafeBrowsingTermsAcceptedAt() != null);
        if (!entity.isReputationSafeBrowsingEnabled()) {
            entity.setSafeBrowsingTermsAcceptedAt(null);
        }
        String requestedAppName = settings.getAppName();
        entity.setAppName((requestedAppName == null || requestedAppName.isBlank()) ? DEFAULT_APP_NAME : requestedAppName.trim());
        entity.setDefaultLanguage(settings.getDefaultLanguage() != null && !settings.getDefaultLanguage().isBlank() ? settings.getDefaultLanguage() : "en");
        entity.setNotifyOnUpload(settings.isNotifyOnUpload());
        entity.setNotifyOnDownload(settings.isNotifyOnDownload());
        entity.setNotifyOnRenewal(settings.isNotifyOnRenewal());
        entity.setNotifyOnDeletion(settings.isNotifyOnDeletion());
        entity.setNotifyOnShareCreate(settings.isNotifyOnShareCreate());
        entity.setNotifyOnShareDownload(settings.isNotifyOnShareDownload());
        entity.setNotifyOnPasteCreate(settings.isNotifyOnPasteCreate());
        entity.setNotifyOnPasteView(settings.isNotifyOnPasteView());
        entity.setNotifyOnPasteEdit(settings.isNotifyOnPasteEdit());
        entity.setNotifyOnStorageDown(settings.isNotifyOnStorageDown());
        entity.setNotifyOnStorageUp(settings.isNotifyOnStorageUp());

        if (settings.getStorageBackend() != null) {
            entity.setStorageBackend(settings.getStorageBackend());
        }
        entity.setS3Endpoint(settings.getS3Endpoint());
        entity.setS3Bucket(settings.getS3Bucket());
        if (settings.getS3Region() != null && !settings.getS3Region().isBlank()) {
            entity.setS3Region(settings.getS3Region());
        }
        entity.setS3AccessKey(settings.getS3AccessKey());
        if (settings.getS3SecretKey() != null && !settings.getS3SecretKey().isBlank()) {
            entity.setS3SecretKey(settings.getS3SecretKey());
        }
        entity.setS3PathStyle(settings.isS3PathStyle());
        entity.setS3KeyPrefix(settings.getS3KeyPrefix() != null ? settings.getS3KeyPrefix() : "");

        entity.setAzureConnectionString(settings.getAzureConnectionString());
        entity.setAzureContainerName(settings.getAzureContainerName());
        entity.setAzureKeyPrefix(settings.getAzureKeyPrefix() != null ? settings.getAzureKeyPrefix() : "");

        entity.setSftpHost(settings.getSftpHost());
        if (settings.getSftpPort() != null) {
            entity.setSftpPort(settings.getSftpPort());
        }
        entity.setSftpUsername(settings.getSftpUsername());
        if (settings.getSftpPassword() != null && !settings.getSftpPassword().isBlank()) {
            entity.setSftpPassword(settings.getSftpPassword());
        }
        if (settings.getSftpPrivateKey() != null && !settings.getSftpPrivateKey().isBlank()) {
            entity.setSftpPrivateKey(settings.getSftpPrivateKey());
        }
        entity.setSftpBasePath(settings.getSftpBasePath() != null ? settings.getSftpBasePath() : "/");
        entity.setSftpKnownHosts(settings.getSftpKnownHosts());

        entity.setWebDavUrl(settings.getWebDavUrl());
        entity.setWebDavUsername(settings.getWebDavUsername());
        if (settings.getWebDavPassword() != null && !settings.getWebDavPassword().isBlank()) {
            entity.setWebDavPassword(settings.getWebDavPassword());
        }
        entity.setWebDavKeyPrefix(settings.getWebDavKeyPrefix() != null ? settings.getWebDavKeyPrefix() : "");

        entity.setBackupScheduleEnabled(settings.isBackupScheduleEnabled());
        entity.setBackupCron(settings.getBackupCron());
        entity.setMaxBackups(settings.getMaxBackups());

        if (clearLogo) {
            entity.setLogoFileName(null);
        } else if (logoFile != null && !logoFile.isEmpty()) {
            try {
                validateLogoFile(logoFile);
                String sanitizedName = logoFile.getOriginalFilename();
                if (sanitizedName == null || sanitizedName.isBlank()) {
                    sanitizedName = "custom-logo";
                }
                sanitizedName = sanitizedName.replaceAll("[^a-zA-Z0-9._-]", "_");
                if (sanitizedName.startsWith(".")) {
                    logger.warn("Rejecting logo filename starting with dot: {}", sanitizedName);
                    return;
                }
                Path brandingDir = AppPaths.BRANDING.toAbsolutePath();
                Files.createDirectories(brandingDir);
                Path resolvedPath = brandingDir.resolve(sanitizedName).normalize();
                if (!resolvedPath.startsWith(brandingDir.normalize())) {
                    logger.warn("Logo path traversal attempt detected: {}", sanitizedName);
                    return;
                }
                logoFile.transferTo(resolvedPath);
                entity.setLogoFileName(resolvedPath.getFileName().toString());
            } catch (IllegalArgumentException e) {
                logger.warn("Logo upload rejected: {}", e.getMessage());
                return;
            } catch (Exception e) {
                throw new RuntimeException("Failed to store logo file", e);
            }
        }

        if (appPassword != null && !appPassword.isEmpty()) {
            entity.setAppPasswordEnabled(settings.isAppPasswordEnabled());
            entity.setAppPasswordHash(BCrypt.hashpw(appPassword, BCrypt.gensalt()));
        } else if (settings.isAppPasswordEnabled()) {
            // Enable only if a hash already exists — never enable with no password set
            if (entity.getAppPasswordHash() == null || entity.getAppPasswordHash().isBlank()) {
                throw new IllegalArgumentException("App password is required when enabling password protection");
            }
            entity.setAppPasswordEnabled(true);
        } else {
            entity.setAppPasswordEnabled(false);
        }

        applicationSettingsRepository.save(entity);
        // Carries the just-saved entity rather than letting listeners read it back through
        // getApplicationSettings() -- see SettingsChangedEvent's javadoc for why that would
        // be a race against this method's beforeInvocation cache eviction.
        eventPublisher.publishEvent(new SettingsChangedEvent(entity));
    }

    /**
     * Returns {@code true} if the minimum required fields for {@code backend} are populated.
     * LOCAL is always considered configured. For remote backends, checks that the
     * identifying/credentials fields are non-blank.
     */
    public boolean isBackendConfigured(org.rostislav.quickdrop.storage.StorageBackend backend) {
        return switch (backend) {
            case LOCAL -> true;
            case S3 -> {
                String bucket = getS3Bucket();
                String key = getS3AccessKey();
                String secret = getS3SecretKey();
                yield !isBlankOrNull(bucket) && !isBlankOrNull(key) && !isBlankOrNull(secret);
            }
            case AZURE -> {
                String conn = getAzureConnectionString();
                String container = getAzureContainerName();
                yield !isBlankOrNull(conn) && !isBlankOrNull(container);
            }
            case SFTP -> {
                String host = getSftpHost();
                String user = getSftpUsername();
                yield !isBlankOrNull(host) && !isBlankOrNull(user);
            }
            case WEBDAV -> !isBlankOrNull(getWebDavUrl());
        };
    }

    /**
     * Tests the current S3 connection settings by calling HeadBucket.
     *
     * @return {@code null} on success; an error message on failure
     */
    public String testS3Connection() {
        s3StorageService.refreshClient();
        return s3StorageService.testConnection();
    }

    /**
     * @return {@code null} on success; an error message on failure
     */
    public String testBackendConnection(StorageBackend backend) {
        return switch (backend) {
            case S3 -> testS3Connection();
            case AZURE -> {
                azureStorageService.refreshClient();
                yield azureStorageService.testConnection();
            }
            case SFTP -> sftpStorageService.testConnection();
            case WEBDAV -> webDavStorageService.testConnection();
            default -> null;
        };
    }

    @CacheEvict(value = "applicationSettings", allEntries = true)
    public void setAdminPassword(String adminPassword) {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalArgumentException("Admin password must not be blank");
        }
        ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
        entity.setAdminPasswordHash(BCrypt.hashpw(adminPassword, BCrypt.gensalt()));
        applicationSettingsRepository.save(entity);
    }

    public long getMaxFileSize() {
        return self.getApplicationSettings().getMaxFileSize();
    }

    public String getFormattedMaxFileSize() {
        return formatFileSize(getMaxFileSize());
    }

    /**
     * @return maximum file retention period in days before scheduled deletion
     */
    public long getMaxFileLifeTime() {
        return self.getApplicationSettings().getMaxFileLifeTime();
    }

    public String getFileStoragePath() {
        return self.getApplicationSettings().getFileStoragePath();
    }

    public String getLogStoragePath() {
        return self.getApplicationSettings().getLogStoragePath();
    }

    /**
     * @return Spring-compatible 6-field cron expression for the scheduled file deletion job
     */
    public String getFileDeletionCron() {
        return self.getApplicationSettings().getFileDeletionCron();
    }

    public boolean isAppPasswordEnabled() {
        return self.getApplicationSettings().isAppPasswordEnabled();
    }

    /**
     * @return BCrypt hash of the application access password
     */
    public String getAppPasswordHash() {
        return self.getApplicationSettings().getAppPasswordHash();
    }

    /**
     * @return BCrypt hash of the admin password
     */
    public String getAdminPasswordHash() {
        return self.getApplicationSettings().getAdminPasswordHash();
    }

    public boolean isFileListPageEnabled() {
        return self.getApplicationSettings().isFileListPageEnabled();
    }

    public boolean isAdminPasswordSet() {
        String hash = self.getApplicationSettings().getAdminPasswordHash();
        return hash != null && !hash.isEmpty();
    }

    /**
     * @return HTTP session lifetime in minutes
     */
    public long getSessionLifetime() {
        return self.getApplicationSettings().getSessionLifetime();
    }

    public boolean isAdminDashboardButtonEnabled() {
        return self.getApplicationSettings().isAdminDashboardButtonEnabled();
    }

    /**
     * @return {@code true} if AES encryption of uploaded files is active
     */
    public boolean isEncryptionEnabled() {
        return self.getApplicationSettings().isEncryptionEnabled();
    }

    public boolean isUploadPasswordEnabled() {
        return self.getApplicationSettings().isUploadPasswordEnabled();
    }

    public boolean isPreviewEnabled() {
        return self.getApplicationSettings().isPreviewEnabled();
    }

    /**
     * @return {@code true} if EXIF/metadata stripping is enabled on image uploads
     */
    public boolean isMetadataStrippingEnabled() {
        return self.getApplicationSettings().isMetadataStrippingEnabled();
    }

    public long getMaxPreviewSizeBytes() {
        return self.getApplicationSettings().getMaxPreviewSizeBytes();
    }

    /**
     * @return identifier of the page shown at {@code /} (e.g. {@code "upload"} or {@code "list"})
     */
    public String getDefaultHomePage() {
        return self.getApplicationSettings().getDefaultHomePage();
    }

    public boolean isKeepIndefinitelyAdminOnly() {
        return self.getApplicationSettings().isKeepIndefinitelyAdminOnly();
    }

    public boolean isHideFromListAdminOnly() {
        return self.getApplicationSettings().isHideFromListAdminOnly();
    }

    public boolean isDiscordWebhookEnabled() {
        return self.getApplicationSettings().isDiscordWebhookEnabled();
    }

    public String getDiscordWebhookUrl() {
        return self.getApplicationSettings().getDiscordWebhookUrl();
    }

    public boolean isEmailNotificationsEnabled() {
        return self.getApplicationSettings().isEmailNotificationsEnabled();
    }

    public String getEmailFrom() {
        return self.getApplicationSettings().getEmailFrom();
    }

    /**
     * @return comma-separated list of email notification recipients
     */
    public String getEmailTo() {
        return self.getApplicationSettings().getEmailTo();
    }

    public String getSmtpHost() {
        return self.getApplicationSettings().getSmtpHost();
    }

    public Integer getSmtpPort() {
        return self.getApplicationSettings().getSmtpPort();
    }

    public String getSmtpUsername() {
        return self.getApplicationSettings().getSmtpUsername();
    }

    public String getSmtpPassword() {
        return self.getApplicationSettings().getSmtpPassword();
    }

    /**
     * @return {@code true} if STARTTLS should be used for SMTP
     */
    public boolean isSmtpUseTls() {
        return self.getApplicationSettings().isSmtpUseTls();
    }

    /**
     * @return {@code true} if implicit SSL/TLS wrapping should be used for SMTP
     */
    public boolean isSmtpUseSsl() {
        return self.getApplicationSettings().isSmtpUseSsl();
    }

    public boolean isNotificationBatchEnabled() {
        return self.getApplicationSettings().isNotificationBatchEnabled();
    }

    /**
     * Simplified links are automatically disabled when share links are globally disabled.
     */
    public boolean isSimplifiedShareLinksEnabled() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isSimplifiedShareLinks() && s.isShareLinksEnabled();
    }

    public boolean isShareLinksEnabled() {
        return self.getApplicationSettings().isShareLinksEnabled();
    }

    public boolean isUploadEnabled() {
        return self.getApplicationSettings().isUploadEnabled();
    }

    public boolean isUploadAdminOnly() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isUploadEnabled() && s.isUploadAdminOnly();
    }

    public boolean isPastebinEnabled() {
        return self.getApplicationSettings().isPastebinEnabled();
    }

    public boolean isShortenerEnabled() {
        return self.getApplicationSettings().isShortenerEnabled();
    }

    public boolean isShortenerAdminOnly() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isShortenerEnabled() && s.isShortenerAdminOnly();
    }

    /**
     * @return the random code length for newly-generated redirect links ({@code /s/{code}})
     */
    public int getShortenerCodeLength() {
        return self.getApplicationSettings().getShortenerCodeLength();
    }

    /**
     * @return the random code length for newly-generated file share tokens
     *         ({@code /share/{token}}), floored at 8 — see
     *         {@link ApplicationSettingsEntity#getShareTokenLength()}
     */
    public int getShareTokenLength() {
        return Math.max(self.getApplicationSettings().getShareTokenLength(), 8);
    }

    /**
     * @return {@code true} if custom aliases are enabled (implies the shortener itself is enabled)
     */
    public boolean isShortenerCustomAliasEnabled() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isShortenerEnabled() && s.isShortenerCustomAliasEnabled();
    }

    public boolean isShortenerCustomAliasAdminOnly() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isShortenerCustomAliasEnabled() && s.isShortenerCustomAliasAdminOnly();
    }

    /**
     * @return {@code "ALWAYS"}, {@code "NEVER"}, or {@code "NON_ADMIN"}
     */
    public String getShortenerInterstitialMode() {
        return self.getApplicationSettings().getShortenerInterstitialMode();
    }

    /**
     * @return {@code "OFF"}, {@code "BLOCKLIST"}, or {@code "ALLOWLIST"}
     */
    public String getShortenerDomainRuleMode() {
        return self.getApplicationSettings().getShortenerDomainRuleMode();
    }

    /**
     * @return newline-separated domain list consulted per {@link #getShortenerDomainRuleMode()}
     */
    public String getShortenerDomainRules() {
        return self.getApplicationSettings().getShortenerDomainRules();
    }

    /**
     * @return {@code true} if {@code X-Forwarded-For}/{@code X-Real-IP} headers should be
     *         trusted for client-IP resolution — only when a real reverse proxy is confirmed
     *         to be in front of this instance
     */
    public boolean isTrustedProxyEnabled() {
        return self.getApplicationSettings().isTrustedProxyEnabled();
    }

    /**
     * @return {@code true} if the session and CSRF cookies' {@code Secure} flag should be
     *         forced on unconditionally, regardless of {@link #isTrustedProxyEnabled()} or any
     *         request header — for a reverse proxy that doesn't send {@code X-Forwarded-Proto}
     */
    public boolean isForceSecureCookiesEnabled() {
        return self.getApplicationSettings().isForceSecureCookiesEnabled();
    }

    /**
     * @return {@code true} if resolving a short link should write an audit-log row —
     *         the link's own use counters update regardless of this setting
     */
    public boolean isShortenerClickLoggingEnabled() {
        return self.getApplicationSettings().isShortenerClickLoggingEnabled();
    }

    /**
     * @return {@code true} if any reputation-provider check should run at all — the master
     *         switch; individual provider flags below only take effect when this is also on
     */
    public boolean isReputationCheckEnabled() {
        return self.getApplicationSettings().isReputationCheckEnabled();
    }

    /**
     * @return {@code true} if the Phishing Army domain blocklist should be checked (licence
     *         already accepted, since this can only be {@code true} via {@link #acceptReputationProviderTerms})
     */
    public boolean isReputationPhishingArmyEnabled() {
        return self.getApplicationSettings().isReputationPhishingArmyEnabled();
    }

    public boolean isReputationUrlhausEnabled() {
        return self.getApplicationSettings().isReputationUrlhausEnabled();
    }

    public boolean isReputationSafeBrowsingEnabled() {
        return self.getApplicationSettings().isReputationSafeBrowsingEnabled();
    }

    /**
     * @return {@code true} if a reputation-provider failure should block link
     *         creation/resolution instead of allowing it (fail closed instead of fail open)
     */
    public boolean isReputationFailClosed() {
        return self.getApplicationSettings().isReputationFailClosed();
    }

    public String getReputationFeedCron() {
        return self.getApplicationSettings().getReputationFeedCron();
    }

    public boolean isActivityRetentionEnabled() {
        return self.getApplicationSettings().isActivityRetentionEnabled();
    }

    public String getActivityRetentionCron() {
        return self.getApplicationSettings().getActivityRetentionCron();
    }

    /**
     * @return retention in days for {@code category}, or {@code 0} to keep it forever
     */
    public int getActivityRetentionDays(EventCategory category) {
        ApplicationSettingsEntity settings = self.getApplicationSettings();
        return switch (category) {
            case FILE -> settings.getActivityRetentionFileDays();
            case PASTE -> settings.getActivityRetentionPasteDays();
            case SHARE -> settings.getActivityRetentionShareDays();
            case SHORTLINK -> settings.getActivityRetentionShortlinkDays();
            case ADMIN -> settings.getActivityRetentionAdminDays();
            case SYSTEM -> settings.getActivityRetentionSystemDays();
        };
    }

    /**
     * @return the configured URLhaus Auth-Key, or {@code null}/blank if not set
     */
    public String getUrlhausAuthKey() {
        return self.getApplicationSettings().getUrlhausAuthKey();
    }

    /**
     * @return the configured Google Safe Browsing API key, or {@code null}/blank if not set
     */
    public String getSafeBrowsingApiKey() {
        return self.getApplicationSettings().getSafeBrowsingApiKey();
    }

    /**
     * Accepts a reputation provider's licence terms and enables it in one atomic step, so a
     * provider can never end up enabled without a recorded acceptance. This is the *only* path
     * that can turn a provider on — the general settings save (see
     * {@link #updateApplicationSettings}) can only turn one off (which also clears the
     * acceptance timestamp, forcing this method to run again before it can be re-enabled).
     *
     * @param providerId one of {@code "phishing_army"}, {@code "urlhaus"}, {@code "safe_browsing"}
     * @return {@code true} if {@code providerId} was recognised and the provider is now enabled
     */
    @Caching(evict = {
            @CacheEvict(value = "applicationSettings", allEntries = true, beforeInvocation = true),
            @CacheEvict(value = "applicationSettings", allEntries = true)
    })
    public boolean acceptReputationProviderTerms(String providerId) {
        ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        switch (providerId == null ? "" : providerId) {
            case "phishing_army" -> {
                entity.setReputationPhishingArmyEnabled(true);
                entity.setPhishingArmyTermsAcceptedAt(now);
            }
            case "urlhaus" -> {
                entity.setReputationUrlhausEnabled(true);
                entity.setUrlhausTermsAcceptedAt(now);
            }
            case "safe_browsing" -> {
                entity.setReputationSafeBrowsingEnabled(true);
                entity.setSafeBrowsingTermsAcceptedAt(now);
            }
            default -> {
                return false;
            }
        }
        applicationSettingsRepository.save(entity);
        eventPublisher.publishEvent(new SettingsChangedEvent(entity));
        return true;
    }

    public Integer getNotificationBatchMinutes() {
        return self.getApplicationSettings().getNotificationBatchMinutes();
    }

    /**
     * @return application display name, falling back to {@code "QuickDrop"} if unset
     */
    public String getAppName() {
        String name = self.getApplicationSettings().getAppName();
        return (name == null || name.isBlank()) ? DEFAULT_APP_NAME : name;
    }

    /**
     * @return BCP 47 UI language tag, falling back to {@code "en"} if unset
     */
    public String getDefaultLanguage() {
        String lang = self.getApplicationSettings().getDefaultLanguage();
        return (lang == null || lang.isBlank()) ? "en" : lang;
    }

    public boolean isNotifyOnUpload() {
        return self.getApplicationSettings().isNotifyOnUpload();
    }

    public boolean isNotifyOnDownload() {
        return self.getApplicationSettings().isNotifyOnDownload();
    }

    public boolean isNotifyOnRenewal() {
        return self.getApplicationSettings().isNotifyOnRenewal();
    }

    public boolean isNotifyOnDeletion() {
        return self.getApplicationSettings().isNotifyOnDeletion();
    }

    public boolean isNotifyOnShareCreate() {
        return self.getApplicationSettings().isNotifyOnShareCreate();
    }

    public boolean isNotifyOnShareDownload() {
        return self.getApplicationSettings().isNotifyOnShareDownload();
    }

    public boolean isNotifyOnPasteCreate() {
        return self.getApplicationSettings().isNotifyOnPasteCreate();
    }

    public boolean isNotifyOnPasteView() {
        return self.getApplicationSettings().isNotifyOnPasteView();
    }

    public boolean isNotifyOnPasteEdit() {
        return self.getApplicationSettings().isNotifyOnPasteEdit();
    }

    public boolean isNotifyOnStorageDown() {
        return self.getApplicationSettings().isNotifyOnStorageDown();
    }

    public boolean isNotifyOnStorageUp() {
        return self.getApplicationSettings().isNotifyOnStorageUp();
    }

    /** @return the active storage backend (LOCAL or S3) */
    public StorageBackend getStorageBackend() {
        StorageBackend backend = self.getApplicationSettings().getStorageBackend();
        return backend != null ? backend : StorageBackend.LOCAL;
    }

    public String getS3Endpoint() { return self.getApplicationSettings().getS3Endpoint(); }

    public String getS3Bucket() { return self.getApplicationSettings().getS3Bucket(); }

    /** @return AWS region (defaults to {@code us-east-1}) */
    public String getS3Region() {
        String r = self.getApplicationSettings().getS3Region();
        return (r == null || r.isBlank()) ? "us-east-1" : r;
    }

    public String getS3AccessKey() { return self.getApplicationSettings().getS3AccessKey(); }

    public String getS3SecretKey() { return self.getApplicationSettings().getS3SecretKey(); }

    public boolean isS3PathStyle() { return self.getApplicationSettings().isS3PathStyle(); }

    /** @return optional object key prefix (e.g. {@code "quickdrop/"}) */
    public String getS3KeyPrefix() {
        String p = self.getApplicationSettings().getS3KeyPrefix();
        return p != null ? p : "";
    }

    public String getAzureConnectionString() {
        return self.getApplicationSettings().getAzureConnectionString();
    }

    public String getAzureContainerName() {
        return self.getApplicationSettings().getAzureContainerName();
    }

    public String getAzureKeyPrefix() {
        String p = self.getApplicationSettings().getAzureKeyPrefix();
        return p != null ? p : "";
    }

    public String getSftpHost() {
        return self.getApplicationSettings().getSftpHost();
    }

    /**
     * @return SFTP server port (default 22)
     */
    public int getSftpPort() {
        Integer p = self.getApplicationSettings().getSftpPort();
        return p != null ? p : 22;
    }

    public String getSftpUsername() {
        return self.getApplicationSettings().getSftpUsername();
    }

    public String getSftpPassword() {
        return self.getApplicationSettings().getSftpPassword();
    }

    /**
     * @return SFTP private key (PEM text)
     */
    public String getSftpPrivateKey() {
        return self.getApplicationSettings().getSftpPrivateKey();
    }

    public String getSftpBasePath() {
        String p = self.getApplicationSettings().getSftpBasePath();
        return p != null ? p : "/";
    }

    public String getSftpKnownHosts() {
        return self.getApplicationSettings().getSftpKnownHosts();
    }

    public String getWebDavUrl() {
        return self.getApplicationSettings().getWebDavUrl();
    }

    public String getWebDavUsername() {
        return self.getApplicationSettings().getWebDavUsername();
    }

    public String getWebDavPassword() {
        return self.getApplicationSettings().getWebDavPassword();
    }

    public String getWebDavKeyPrefix() {
        String p = self.getApplicationSettings().getWebDavKeyPrefix();
        return p != null ? p : "";
    }

    /**
     * Falls back to the built-in favicon if no custom logo is configured or the stored
     * file is missing.
     */
    public String getLogoPath() {
        String fileName = self.getApplicationSettings().getLogoFileName();
        if (fileName == null || fileName.isBlank()) {
            return DEFAULT_FAVICON_PATH;
        }
        Path brandingDir = AppPaths.BRANDING.toAbsolutePath();
        Path candidate = brandingDir.resolve(fileName).normalize();
        if (!candidate.startsWith(brandingDir.normalize())) {
            logger.warn("Stored logo filename escapes branding directory, ignoring: {}", fileName);
            return DEFAULT_FAVICON_PATH;
        }
        if (Files.exists(candidate)) {
            return "/branding/" + candidate.getFileName();
        }
        return DEFAULT_FAVICON_PATH;
    }

    /**
     * Validates a logo {@link MultipartFile} before persisting it.
     *
     * <p>Checks the declared MIME type, the file extension, the first 100 bytes for
     * SVG/XML signatures, and enforces a 2 MB size cap.
     *
     * @throws IllegalArgumentException if the file fails any validation rule
     */
    private void validateLogoFile(MultipartFile logoFile) throws IllegalArgumentException {
        if (logoFile == null || logoFile.isEmpty()) return;

        String contentType = logoFile.getContentType();
        String originalFilename = logoFile.getOriginalFilename();

        // Reject non-raster types up front — SVG can embed scripts
        java.util.Set<String> allowedTypes = java.util.Set.of(
                "image/png", "image/jpeg", "image/gif", "image/webp", "image/x-icon"
        );
        if (contentType == null || !allowedTypes.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Logo must be a PNG, JPEG, GIF, WebP, or ICO image. SVG is not allowed.");
        }

        if (originalFilename != null) {
            String lower = originalFilename.toLowerCase();
            if (lower.endsWith(".svg") || lower.endsWith(".xml") || lower.endsWith(".html")) {
                throw new IllegalArgumentException("SVG and XML files are not allowed as logo.");
            }
        }

        // Magic-byte check catches SVG disguised as another MIME type
        try {
            byte[] header = logoFile.getBytes();
            if (header.length > 4) {
                String headerStr = new String(header, 0, Math.min(100, header.length), StandardCharsets.UTF_8);
                if (headerStr.contains("<svg") || headerStr.contains("<?xml") || headerStr.contains("<!DOCTYPE")) {
                    throw new IllegalArgumentException(
                            "SVG/XML content detected. Only raster images are allowed as logo.");
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not read logo file: " + e.getMessage());
        }

        if (logoFile.getSize() > 2L * 1024L * 1024L) {
            throw new IllegalArgumentException("Logo file exceeds 2 MB limit.");
        }
    }
}
