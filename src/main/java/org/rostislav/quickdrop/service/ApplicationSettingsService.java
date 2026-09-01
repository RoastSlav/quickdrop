package org.rostislav.quickdrop.service;

import jakarta.annotation.PostConstruct;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.EventCategory;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.rostislav.quickdrop.storage.*;
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
     * Called once by Spring after the bean is constructed.
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
            defaults.setDefaultHomePage("upload");
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
            defaults.setShortenerInterstitialMode("NON_ADMIN");
            defaults.setShortenerDomainRuleMode("OFF");
            defaults.setShortenerDomainRules("");
            defaults.setTrustedProxyEnabled(false);
            defaults.setShortenerClickLoggingEnabled(true);
            defaults.setReputationCheckEnabled(false);
            defaults.setReputationPhishingArmyEnabled(false);
            defaults.setReputationUrlhausEnabled(false);
            defaults.setReputationSafeBrowsingEnabled(false);
            defaults.setReputationFailClosed(false);
            defaults.setReputationFeedCron("0 0 4 * * *");
            defaults.setActivityRetentionEnabled(false);
            defaults.setActivityRetentionCron("0 30 3 * * *");
            defaults.setActivityRetentionFileDays(365);
            defaults.setActivityRetentionPasteDays(365);
            defaults.setActivityRetentionShareDays(365);
            defaults.setActivityRetentionShortlinkDays(365);
            defaults.setActivityRetentionAdminDays(365);
            defaults.setActivityRetentionSystemDays(365);
            defaults.setAppName("QuickDrop");
            defaults.setLogoFileName(null);
            defaults.setDefaultLanguage("en");
            return applicationSettingsRepository.save(defaults);
        });

        boolean dirty = false;
        if (settings.getAppName() == null || settings.getAppName().isBlank()) {
            settings.setAppName("QuickDrop");
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
            settings.setDefaultHomePage("upload");
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
            settings.setActivityRetentionCron("0 30 3 * * *");
            dirty = true;
        }
        if (settings.getShortenerInterstitialMode() == null || settings.getShortenerInterstitialMode().isBlank()) {
            settings.setShortenerInterstitialMode("NON_ADMIN");
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
     * Returns the settings entity, loading it from the database on first call and caching
     * the result. All subsequent calls return the cached instance until the cache is evicted.
     *
     * @return the single application settings entity (ID 1)
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
     *
     * @param settings the incoming view-model carrying the requested home page and feature flags
     * @return a valid home-page identifier ({@code "upload"}, {@code "list"}, {@code "paste"},
     * or {@code "none"})
     */
    private String coerceDefaultHomePage(ApplicationSettingsViewModel settings) {
        String page = settings.getDefaultHomePage();
        if (page == null) return "upload";
        boolean uploadPublic = settings.isUploadEnabled() && !settings.isUploadAdminOnly();
        boolean listEnabled = settings.isFileListPageEnabled();
        boolean pasteEnabled = settings.isPastebinEnabled();
        switch (page.toLowerCase()) {
            case "upload":
                if (!uploadPublic) page = listEnabled ? "list" : pasteEnabled ? "paste" : "none";
                break;
            case "list":
                if (!listEnabled) page = uploadPublic ? "upload" : pasteEnabled ? "paste" : "none";
                break;
            case "paste":
                if (!pasteEnabled) page = uploadPublic ? "upload" : listEnabled ? "list" : "none";
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
     * @param settings    the updated settings from the admin form
     * @param appPassword new plaintext app password, or {@code null}/{@code ""} to leave unchanged
     * @param logoFile    optional new logo image to store
     * @param clearLogo   if {@code true}, removes the current custom logo
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
        // Validate Discord webhook URL before persisting — only accept https://discord.com
        // or https://discordapp.com to prevent SSRF via saved webhook URLs.
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
        // Floored at 8, not just >0: this token is the only thing standing between an
        // anonymous caller and the file's bytes, and an admin lowering it to 4 "to make
        // links prettier" silently reopens the enumeration the split exists to close.
        // The shortener code length above stays freely adjustable — guessing one of those
        // only reveals a destination URL.
        entity.setShareTokenLength(Math.max(settings.getShareTokenLength(), 8));
        boolean shortenerCustomAliasEnabled = shortenerEnabled && settings.isShortenerCustomAliasEnabled();
        entity.setShortenerCustomAliasEnabled(shortenerCustomAliasEnabled);
        entity.setShortenerCustomAliasAdminOnly(shortenerCustomAliasEnabled && settings.isShortenerCustomAliasAdminOnly());
        String interstitialMode = settings.getShortenerInterstitialMode();
        entity.setShortenerInterstitialMode(
                java.util.Set.of("ALWAYS", "NEVER", "NON_ADMIN").contains(interstitialMode == null ? "" : interstitialMode)
                        ? interstitialMode : "NON_ADMIN");
        String domainRuleMode = settings.getShortenerDomainRuleMode();
        entity.setShortenerDomainRuleMode(
                java.util.Set.of("OFF", "BLOCKLIST", "ALLOWLIST").contains(domainRuleMode == null ? "" : domainRuleMode)
                        ? domainRuleMode : "OFF");
        entity.setShortenerDomainRules(settings.getShortenerDomainRules() != null ? settings.getShortenerDomainRules() : "");
        entity.setTrustedProxyEnabled(settings.isTrustedProxyEnabled());
        entity.setShortenerClickLoggingEnabled(settings.isShortenerClickLoggingEnabled());
        entity.setReputationCheckEnabled(settings.isReputationCheckEnabled());
        entity.setReputationFailClosed(settings.isReputationFailClosed());
        String reputationFeedCron = settings.getReputationFeedCron();
        entity.setReputationFeedCron(reputationFeedCron != null && !reputationFeedCron.isBlank() ? reputationFeedCron : "0 0 4 * * *");
        entity.setActivityRetentionEnabled(settings.isActivityRetentionEnabled());
        String activityRetentionCron = settings.getActivityRetentionCron();
        entity.setActivityRetentionCron(activityRetentionCron != null && !activityRetentionCron.isBlank() ? activityRetentionCron : "0 30 3 * * *");
        // A negative would put the cutoff in the future and purge unexpired rows; 0 = forever.
        entity.setActivityRetentionFileDays(Math.max(settings.getActivityRetentionFileDays(), 0));
        entity.setActivityRetentionPasteDays(Math.max(settings.getActivityRetentionPasteDays(), 0));
        entity.setActivityRetentionShareDays(Math.max(settings.getActivityRetentionShareDays(), 0));
        entity.setActivityRetentionShortlinkDays(Math.max(settings.getActivityRetentionShortlinkDays(), 0));
        entity.setActivityRetentionAdminDays(Math.max(settings.getActivityRetentionAdminDays(), 0));
        entity.setActivityRetentionSystemDays(Math.max(settings.getActivityRetentionSystemDays(), 0));
        entity.setUrlhausAuthKey(settings.getUrlhausAuthKey());
        entity.setSafeBrowsingApiKey(settings.getSafeBrowsingApiKey());
        // Each provider can only be (re-)enabled through #acceptReputationProviderTerms, never
        // through this general settings save -- turning it off here also clears the acceptance
        // timestamp, so re-enabling later always re-prompts for licence acceptance, per the
        // "off then back on re-prompts" requirement.
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
        entity.setAppName((requestedAppName == null || requestedAppName.isBlank()) ? "QuickDrop" : requestedAppName.trim());
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

        // S3 / storage backend settings
        if (settings.getStorageBackend() != null) {
            entity.setStorageBackend(settings.getStorageBackend());
        }
        entity.setS3Endpoint(settings.getS3Endpoint());
        entity.setS3Bucket(settings.getS3Bucket());
        if (settings.getS3Region() != null && !settings.getS3Region().isBlank()) {
            entity.setS3Region(settings.getS3Region());
        }
        entity.setS3AccessKey(settings.getS3AccessKey());
        // Only overwrite the secret key when a new non-blank value is provided
        if (settings.getS3SecretKey() != null && !settings.getS3SecretKey().isBlank()) {
            entity.setS3SecretKey(settings.getS3SecretKey());
        }
        entity.setS3PathStyle(settings.isS3PathStyle());
        entity.setS3KeyPrefix(settings.getS3KeyPrefix() != null ? settings.getS3KeyPrefix() : "");

        // Azure Blob Storage settings
        entity.setAzureConnectionString(settings.getAzureConnectionString());
        entity.setAzureContainerName(settings.getAzureContainerName());
        entity.setAzureKeyPrefix(settings.getAzureKeyPrefix() != null ? settings.getAzureKeyPrefix() : "");

        // SFTP settings
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

        // WebDAV settings
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
                // Fix 2a: reject filenames starting with dot
                if (sanitizedName.startsWith(".")) {
                    logger.warn("Rejecting logo filename starting with dot: {}", sanitizedName);
                    return;
                }
                Path brandingDir = Path.of("branding").toAbsolutePath();
                Files.createDirectories(brandingDir);
                // Fix 2b: path-confinement check
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
     * Tests the connection for the specified backend.
     *
     * @param backend the backend to test
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

    /**
     * Hashes and persists a new admin password, evicting the settings cache.
     *
     * @param adminPassword plaintext admin password to hash and store
     */
    @CacheEvict(value = "applicationSettings", allEntries = true)
    public void setAdminPassword(String adminPassword) {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalArgumentException("Admin password must not be blank");
        }
        ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
        entity.setAdminPasswordHash(BCrypt.hashpw(adminPassword, BCrypt.gensalt()));
        applicationSettingsRepository.save(entity);
    }

    /**
     * @return maximum allowed file upload size in bytes
     */
    public long getMaxFileSize() {
        return self.getApplicationSettings().getMaxFileSize();
    }

    /**
     * @return {@link #getMaxFileSize()} formatted as a human-readable string (e.g. "1 GB")
     */
    public String getFormattedMaxFileSize() {
        return formatFileSize(getMaxFileSize());
    }

    /**
     * @return maximum file retention period in days before scheduled deletion
     */
    public long getMaxFileLifeTime() {
        return self.getApplicationSettings().getMaxFileLifeTime();
    }

    /**
     * @return filesystem path where uploaded files are stored
     */
    public String getFileStoragePath() {
        return self.getApplicationSettings().getFileStoragePath();
    }

    /**
     * @return filesystem path where application logs are stored
     */
    public String getLogStoragePath() {
        return self.getApplicationSettings().getLogStoragePath();
    }

    /**
     * @return Spring-compatible 6-field cron expression for the scheduled file deletion job
     */
    public String getFileDeletionCron() {
        return self.getApplicationSettings().getFileDeletionCron();
    }

    /**
     * @return {@code true} if an application-level access password is required
     */
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

    /**
     * @return {@code true} if the public file list page is enabled
     */
    public boolean isFileListPageEnabled() {
        return self.getApplicationSettings().isFileListPageEnabled();
    }

    /**
     * @return {@code true} if an admin password has been set (hash is non-empty)
     */
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

    /**
     * @return {@code true} if the admin dashboard button is visible on the public UI
     */
    public boolean isAdminDashboardButtonEnabled() {
        return self.getApplicationSettings().isAdminDashboardButtonEnabled();
    }

    /**
     * @return {@code true} if AES encryption of uploaded files is active
     */
    public boolean isEncryptionEnabled() {
        return self.getApplicationSettings().isEncryptionEnabled();
    }

    /**
     * @return {@code true} if per-file upload passwords are allowed
     */
    public boolean isUploadPasswordEnabled() {
        return self.getApplicationSettings().isUploadPasswordEnabled();
    }

    /**
     * @return {@code true} if in-browser file preview is enabled
     */
    public boolean isPreviewEnabled() {
        return self.getApplicationSettings().isPreviewEnabled();
    }

    /**
     * @return {@code true} if EXIF/metadata stripping is enabled on image uploads
     */
    public boolean isMetadataStrippingEnabled() {
        return self.getApplicationSettings().isMetadataStrippingEnabled();
    }

    /**
     * @return maximum file size in bytes eligible for browser preview
     */
    public long getMaxPreviewSizeBytes() {
        return self.getApplicationSettings().getMaxPreviewSizeBytes();
    }

    /**
     * @return identifier of the page shown at {@code /} (e.g. {@code "upload"} or {@code "list"})
     */
    public String getDefaultHomePage() {
        return self.getApplicationSettings().getDefaultHomePage();
    }

    /**
     * @return {@code true} if only admins can mark files as "keep indefinitely"
     */
    public boolean isKeepIndefinitelyAdminOnly() {
        return self.getApplicationSettings().isKeepIndefinitelyAdminOnly();
    }

    /**
     * @return {@code true} if only admins can hide files from the public list
     */
    public boolean isHideFromListAdminOnly() {
        return self.getApplicationSettings().isHideFromListAdminOnly();
    }

    /**
     * @return {@code true} if Discord webhook notifications are enabled
     */
    public boolean isDiscordWebhookEnabled() {
        return self.getApplicationSettings().isDiscordWebhookEnabled();
    }

    /**
     * @return configured Discord webhook URL
     */
    public String getDiscordWebhookUrl() {
        return self.getApplicationSettings().getDiscordWebhookUrl();
    }

    /**
     * @return {@code true} if email notifications are enabled
     */
    public boolean isEmailNotificationsEnabled() {
        return self.getApplicationSettings().isEmailNotificationsEnabled();
    }

    /**
     * @return the "From" address used for outgoing email notifications
     */
    public String getEmailFrom() {
        return self.getApplicationSettings().getEmailFrom();
    }

    /**
     * @return comma-separated list of email notification recipients
     */
    public String getEmailTo() {
        return self.getApplicationSettings().getEmailTo();
    }

    /**
     * @return SMTP host for outgoing mail
     */
    public String getSmtpHost() {
        return self.getApplicationSettings().getSmtpHost();
    }

    /**
     * @return SMTP port for outgoing mail
     */
    public Integer getSmtpPort() {
        return self.getApplicationSettings().getSmtpPort();
    }

    /**
     * @return SMTP authentication username
     */
    public String getSmtpUsername() {
        return self.getApplicationSettings().getSmtpUsername();
    }

    /**
     * @return SMTP authentication password
     */
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
     * @return {@code true} if SSL/TLS wrapping should be used for SMTP
     */
    public boolean isSmtpUseSsl() {
        return self.getApplicationSettings().isSmtpUseSsl();
    }

    /**
     * @return {@code true} if notification batching is enabled
     */
    public boolean isNotificationBatchEnabled() {
        return self.getApplicationSettings().isNotificationBatchEnabled();
    }

    /**
     * Returns {@code true} if simplified share links are enabled.
     * Simplified links are automatically disabled when share links are globally disabled.
     */
    public boolean isSimplifiedShareLinksEnabled() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isSimplifiedShareLinks() && s.isShareLinksEnabled();
    }

    /**
     * @return {@code true} if share link generation is enabled
     */
    public boolean isShareLinksEnabled() {
        return self.getApplicationSettings().isShareLinksEnabled();
    }

    /**
     * @return {@code true} if file uploads are enabled
     */
    public boolean isUploadEnabled() {
        return self.getApplicationSettings().isUploadEnabled();
    }

    /**
     * @return {@code true} if uploads are restricted to admin sessions only
     */
    public boolean isUploadAdminOnly() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isUploadEnabled() && s.isUploadAdminOnly();
    }

    /**
     * @return {@code true} if the pastebin feature is enabled
     */
    public boolean isPastebinEnabled() {
        return self.getApplicationSettings().isPastebinEnabled();
    }

    /**
     * @return {@code true} if the link-shortener feature is enabled
     */
    public boolean isShortenerEnabled() {
        return self.getApplicationSettings().isShortenerEnabled();
    }

    /**
     * @return {@code true} if redirect-link creation is restricted to admin sessions
     */
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

    /**
     * @return {@code true} if only admin sessions may request a custom alias
     */
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

    /**
     * @return {@code true} if the URLhaus online-malware-URL dataset should be checked
     */
    public boolean isReputationUrlhausEnabled() {
        return self.getApplicationSettings().isReputationUrlhausEnabled();
    }

    /**
     * @return {@code true} if Google Safe Browsing {@code hashes.search} should be queried
     */
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

    /**
     * @return cron expression for the scheduled Phishing Army / URLhaus feed refresh
     */
    public String getReputationFeedCron() {
        return self.getApplicationSettings().getReputationFeedCron();
    }

    /**
     * @return {@code true} if the activity-log retention sweep should run
     */
    public boolean isActivityRetentionEnabled() {
        return self.getApplicationSettings().isActivityRetentionEnabled();
    }

    /**
     * @return cron expression for the activity-log archive-and-purge sweep
     */
    public String getActivityRetentionCron() {
        return self.getApplicationSettings().getActivityRetentionCron();
    }

    /**
     * Days of activity history kept for a category before it is archived and deleted.
     *
     * @param category the event category
     * @return retention in days, or {@code 0} to keep that category forever
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

    /**
     * @return notification batch flush interval in minutes
     */
    public Integer getNotificationBatchMinutes() {
        return self.getApplicationSettings().getNotificationBatchMinutes();
    }

    /**
     * Returns the application display name, falling back to {@code "QuickDrop"} if
     * the stored value is blank or null.
     *
     * @return application name
     */
    public String getAppName() {
        String name = self.getApplicationSettings().getAppName();
        return (name == null || name.isBlank()) ? "QuickDrop" : name;
    }

    /**
     * Returns the default UI language code, falling back to {@code "en"} if unset.
     *
     * @return BCP 47 language tag (e.g. {@code "en"}, {@code "de"})
     */
    public String getDefaultLanguage() {
        String lang = self.getApplicationSettings().getDefaultLanguage();
        return (lang == null || lang.isBlank()) ? "en" : lang;
    }

    /**
     * @return {@code true} if upload events should trigger a notification
     */
    public boolean isNotifyOnUpload() {
        return self.getApplicationSettings().isNotifyOnUpload();
    }

    /**
     * @return {@code true} if direct-download events should trigger a notification
     */
    public boolean isNotifyOnDownload() {
        return self.getApplicationSettings().isNotifyOnDownload();
    }

    /**
     * @return {@code true} if file-renewal events should trigger a notification
     */
    public boolean isNotifyOnRenewal() {
        return self.getApplicationSettings().isNotifyOnRenewal();
    }

    /**
     * @return {@code true} if file-deletion events should trigger a notification
     */
    public boolean isNotifyOnDeletion() {
        return self.getApplicationSettings().isNotifyOnDeletion();
    }

    /**
     * @return {@code true} if share-token creation events should trigger a notification
     */
    public boolean isNotifyOnShareCreate() {
        return self.getApplicationSettings().isNotifyOnShareCreate();
    }

    /**
     * @return {@code true} if share-link download events should trigger a notification
     */
    public boolean isNotifyOnShareDownload() {
        return self.getApplicationSettings().isNotifyOnShareDownload();
    }

    /**
     * @return {@code true} if paste-creation events should trigger a notification
     */
    public boolean isNotifyOnPasteCreate() {
        return self.getApplicationSettings().isNotifyOnPasteCreate();
    }

    /**
     * @return {@code true} if paste-view events should trigger a notification
     */
    public boolean isNotifyOnPasteView() {
        return self.getApplicationSettings().isNotifyOnPasteView();
    }

    /**
     * @return {@code true} if paste-edit events should trigger a notification
     */
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

    /** @return S3-compatible endpoint override URL, or {@code null} for standard AWS */
    public String getS3Endpoint() { return self.getApplicationSettings().getS3Endpoint(); }

    /** @return S3 bucket name */
    public String getS3Bucket() { return self.getApplicationSettings().getS3Bucket(); }

    /** @return AWS region (defaults to {@code us-east-1}) */
    public String getS3Region() {
        String r = self.getApplicationSettings().getS3Region();
        return (r == null || r.isBlank()) ? "us-east-1" : r;
    }

    /** @return S3 access key ID */
    public String getS3AccessKey() { return self.getApplicationSettings().getS3AccessKey(); }

    /** @return S3 secret access key */
    public String getS3SecretKey() { return self.getApplicationSettings().getS3SecretKey(); }

    /** @return {@code true} if path-style S3 URLs should be used */
    public boolean isS3PathStyle() { return self.getApplicationSettings().isS3PathStyle(); }

    /** @return optional object key prefix (e.g. {@code "quickdrop/"}) */
    public String getS3KeyPrefix() {
        String p = self.getApplicationSettings().getS3KeyPrefix();
        return p != null ? p : "";
    }

    // ── Azure Blob Storage getters ─────────────────────────────────────────────

    /**
     * @return Azure Blob Storage connection string
     */
    public String getAzureConnectionString() {
        return self.getApplicationSettings().getAzureConnectionString();
    }

    /**
     * @return Azure Blob Storage container name
     */
    public String getAzureContainerName() {
        return self.getApplicationSettings().getAzureContainerName();
    }

    /**
     * @return optional Azure blob key prefix
     */
    public String getAzureKeyPrefix() {
        String p = self.getApplicationSettings().getAzureKeyPrefix();
        return p != null ? p : "";
    }

    // ── SFTP getters ───────────────────────────────────────────────────────────

    /**
     * @return SFTP server hostname
     */
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

    /**
     * @return SFTP username
     */
    public String getSftpUsername() {
        return self.getApplicationSettings().getSftpUsername();
    }

    /**
     * @return SFTP password
     */
    public String getSftpPassword() {
        return self.getApplicationSettings().getSftpPassword();
    }

    /**
     * @return SFTP private key (PEM text)
     */
    public String getSftpPrivateKey() {
        return self.getApplicationSettings().getSftpPrivateKey();
    }

    /**
     * @return SFTP base path on the remote server
     */
    public String getSftpBasePath() {
        String p = self.getApplicationSettings().getSftpBasePath();
        return p != null ? p : "/";
    }

    /**
     * @return SFTP known_hosts content
     */
    public String getSftpKnownHosts() {
        return self.getApplicationSettings().getSftpKnownHosts();
    }

    // ── WebDAV getters ─────────────────────────────────────────────────────────

    /**
     * @return WebDAV server URL
     */
    public String getWebDavUrl() {
        return self.getApplicationSettings().getWebDavUrl();
    }

    /**
     * @return WebDAV username
     */
    public String getWebDavUsername() {
        return self.getApplicationSettings().getWebDavUsername();
    }

    /**
     * @return WebDAV password
     */
    public String getWebDavPassword() {
        return self.getApplicationSettings().getWebDavPassword();
    }

    /**
     * @return optional WebDAV key prefix
     */
    public String getWebDavKeyPrefix() {
        String p = self.getApplicationSettings().getWebDavKeyPrefix();
        return p != null ? p : "";
    }

    /**
     * Returns the URL path for the application logo.
     *
     * <p>Checks whether the stored {@code logoFileName} resolves to an existing file
     * inside the {@code branding/} directory. Falls back to the built-in favicon
     * if no custom logo is configured or the file is missing.
     *
     * @return a server-relative URL path to the logo image
     */
    public String getLogoPath() {
        String fileName = self.getApplicationSettings().getLogoFileName();
        if (fileName == null || fileName.isBlank()) {
            return "/images/favicon.png";
        }
        Path brandingDir = Path.of("branding").toAbsolutePath();
        // Fix 2: path-confinement check when serving the stored filename
        Path candidate = brandingDir.resolve(fileName).normalize();
        if (!candidate.startsWith(brandingDir.normalize())) {
            logger.warn("Stored logo filename escapes branding directory, ignoring: {}", fileName);
            return "/images/favicon.png";
        }
        if (Files.exists(candidate)) {
            return "/branding/" + candidate.getFileName();
        }
        return "/images/favicon.png";
    }

    /**
     * Validates a logo {@link MultipartFile} before persisting it.
     *
     * <p>Checks the declared MIME type, the file extension, the first 100 bytes for
     * SVG/XML signatures, and enforces a 2 MB size cap.
     *
     * @param logoFile the uploaded file to validate (may be {@code null} or empty)
     * @throws IllegalArgumentException if the file fails any validation rule
     */
    private void validateLogoFile(MultipartFile logoFile) throws IllegalArgumentException {
        if (logoFile == null || logoFile.isEmpty()) return;

        String contentType = logoFile.getContentType();
        String originalFilename = logoFile.getOriginalFilename();

        // Check declared MIME type — reject SVG and other non-raster types
        java.util.Set<String> allowedTypes = java.util.Set.of(
                "image/png", "image/jpeg", "image/gif", "image/webp", "image/x-icon"
        );
        if (contentType == null || !allowedTypes.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Logo must be a PNG, JPEG, GIF, WebP, or ICO image. SVG is not allowed.");
        }

        // Check file extension
        if (originalFilename != null) {
            String lower = originalFilename.toLowerCase();
            if (lower.endsWith(".svg") || lower.endsWith(".xml") || lower.endsWith(".html")) {
                throw new IllegalArgumentException("SVG and XML files are not allowed as logo.");
            }
        }

        // Check magic bytes — catch SVG disguised as another MIME type
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

        // Size limit: 2 MB maximum
        if (logoFile.getSize() > 2L * 1024L * 1024L) {
            throw new IllegalArgumentException("Logo file exceeds 2 MB limit.");
        }
    }
}
