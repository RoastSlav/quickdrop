package org.rostislav.quickdrop.service;

import jakarta.annotation.PostConstruct;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>{@link #updateApplicationSettings} evicts the cache, persists all changed
 * fields, updates the cleanup schedule, and triggers a Spring Cloud context refresh.
 */
@Service
public class ApplicationSettingsService {
    private final ApplicationSettingsRepository applicationSettingsRepository;
    private final ContextRefresher contextRefresher;

    /**
     * Self-reference for routing calls through the Spring AOP proxy.
     */
    @Lazy
    @Autowired
    private ApplicationSettingsService self;

    /**
     * Lazily injected {@link ScheduleService}.
     */
    @Lazy
    @Autowired
    private ScheduleService scheduleService;

    public ApplicationSettingsService(ApplicationSettingsRepository applicationSettingsRepository,
                                      @Qualifier("configDataContextRefresher") ContextRefresher contextRefresher) {
        this.contextRefresher = contextRefresher;
        this.applicationSettingsRepository = applicationSettingsRepository;
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
            defaults.setLogStoragePath("logs");
            defaults.setFileDeletionCron("0 0 2 * * *");
            defaults.setAppPasswordEnabled(false);
            defaults.setAppPasswordHash("");
            defaults.setAdminPasswordHash("");
            defaults.setSessionLifetime(30);
            defaults.setFileListPageEnabled(true);
            defaults.setAdminDashboardButtonEnabled(true);
            defaults.setDisableEncryption(false);
            defaults.setDisableUploadPassword(false);
            defaults.setDisablePreview(false);
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
            defaults.setShareLinksDisabled(false);
            defaults.setPastebinEnabled(true);
            defaults.setAppName("QuickDrop");
            defaults.setLogoFileName(null);
            defaults.setDefaultLanguage("en");
            return applicationSettingsRepository.save(defaults);
        });

        if (settings.getAppName() == null || settings.getAppName().isBlank()) {
            settings.setAppName("QuickDrop");
            applicationSettingsRepository.save(settings);
        }
    }

    /**
     * Fires the initial cleanup schedule once the application context is fully started,
     * using the persisted cron expression and max file lifetime.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ApplicationSettingsEntity settings = applicationSettingsRepository.findById(1L).orElseThrow();
        scheduleService.updateSchedule(settings.getFileDeletionCron(), settings.getMaxFileLifeTime());
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

    /**
     * Persists all settings from the view-model, evicts the settings cache, updates the
     * cleanup schedule, and triggers a Spring Cloud context refresh.
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
    @CacheEvict(value = "applicationSettings", allEntries = true)
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
        entity.setDisableUploadPassword(settings.isDisableUploadPassword());
        if (settings.isDisableUploadPassword()) {
            entity.setDisableEncryption(true);
        } else {
            entity.setDisableEncryption(settings.isEncryptionDisabled());
        }
        entity.setDisablePreview(settings.isDisablePreview());
        entity.setMetadataStrippingEnabled(settings.isMetadataStrippingEnabled());
        entity.setMaxPreviewSizeBytes(settings.getMaxPreviewSizeBytes());
        entity.setDefaultHomePage(settings.getDefaultHomePage());
        entity.setKeepIndefinitelyAdminOnly(settings.isKeepIndefinitelyAdminOnly());
        entity.setHideFromListAdminOnly(settings.isHideFromListAdminOnly());
        boolean shareLinksDisabled = settings.isShareLinksDisabled();
        entity.setShareLinksDisabled(shareLinksDisabled);
        entity.setDiscordWebhookEnabled(settings.isDiscordWebhookEnabled());
        entity.setDiscordWebhookUrl(settings.getDiscordWebhookUrl());
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
        entity.setSimplifiedShareLinks(shareLinksDisabled ? false : settings.isSimplifiedShareLinks());
        entity.setPastebinEnabled(settings.isPastebinEnabled());
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

        if (clearLogo) {
            entity.setLogoFileName(null);
        } else if (logoFile != null && !logoFile.isEmpty()) {
            try {
                String sanitizedName = logoFile.getOriginalFilename();
                if (sanitizedName == null || sanitizedName.isBlank()) {
                    sanitizedName = "custom-logo";
                }
                sanitizedName = sanitizedName.replaceAll("[^a-zA-Z0-9._-]", "_");
                Path brandingDir = Path.of("branding").toAbsolutePath();
                Files.createDirectories(brandingDir);
                Path targetPath = brandingDir.resolve(sanitizedName);
                logoFile.transferTo(targetPath);
                entity.setLogoFileName(targetPath.getFileName().toString());
            } catch (Exception e) {
                throw new RuntimeException("Failed to store logo file", e);
            }
        }

        if (appPassword != null && !appPassword.isEmpty()) {
            entity.setAppPasswordEnabled(settings.isAppPasswordEnabled());
            entity.setAppPasswordHash(BCrypt.hashpw(appPassword, BCrypt.gensalt()));
        } else if (!settings.isAppPasswordEnabled()) {
            entity.setAppPasswordEnabled(false);
        }

        applicationSettingsRepository.save(entity);
        scheduleService.updateSchedule(entity.getFileDeletionCron(), entity.getMaxFileLifeTime());
        contextRefresher.refresh();
    }

    /**
     * Hashes and persists a new admin password, evicting the settings cache.
     *
     * @param adminPassword plaintext admin password to hash and store
     */
    @CacheEvict(value = "applicationSettings", allEntries = true)
    public void setAdminPassword(String adminPassword) {
        ApplicationSettingsEntity entity = applicationSettingsRepository.findById(1L).orElseThrow();
        entity.setAdminPasswordHash(BCrypt.hashpw(adminPassword, BCrypt.gensalt()));
        applicationSettingsRepository.save(entity);
    }

    /** @return maximum allowed file upload size in bytes */
    public long getMaxFileSize() {
        return self.getApplicationSettings().getMaxFileSize();
    }

    /** @return {@link #getMaxFileSize()} formatted as a human-readable string (e.g. "1 GB") */
    public String getFormattedMaxFileSize() {
        return formatFileSize(getMaxFileSize());
    }

    /** @return maximum file retention period in days before scheduled deletion */
    public long getMaxFileLifeTime() {
        return self.getApplicationSettings().getMaxFileLifeTime();
    }

    /** @return filesystem path where uploaded files are stored */
    public String getFileStoragePath() {
        return self.getApplicationSettings().getFileStoragePath();
    }

    /** @return filesystem path where application logs are stored */
    public String getLogStoragePath() {
        return self.getApplicationSettings().getLogStoragePath();
    }

    /** @return Spring-compatible 6-field cron expression for the scheduled file deletion job */
    public String getFileDeletionCron() {
        return self.getApplicationSettings().getFileDeletionCron();
    }

    /** @return {@code true} if an application-level access password is required */
    public boolean isAppPasswordEnabled() {
        return self.getApplicationSettings().isAppPasswordEnabled();
    }

    /** @return BCrypt hash of the application access password */
    public String getAppPasswordHash() {
        return self.getApplicationSettings().getAppPasswordHash();
    }

    /** @return BCrypt hash of the admin password */
    public String getAdminPasswordHash() {
        return self.getApplicationSettings().getAdminPasswordHash();
    }

    /** @return {@code true} if the public file list page is enabled */
    public boolean isFileListPageEnabled() {
        return self.getApplicationSettings().isFileListPageEnabled();
    }

    /** @return {@code true} if an admin password has been set (hash is non-empty) */
    public boolean isAdminPasswordSet() {
        return !self.getApplicationSettings().getAdminPasswordHash().isEmpty();
    }

    /** @return HTTP session lifetime in minutes */
    public long getSessionLifetime() {
        return self.getApplicationSettings().getSessionLifetime();
    }

    /** @return {@code true} if the admin dashboard button is visible on the public UI */
    public boolean isAdminDashboardButtonEnabled() {
        return self.getApplicationSettings().isAdminDashboardButtonEnabled();
    }

    /** @return {@code true} if AES encryption of uploaded files is active */
    public boolean isEncryptionEnabled() {
        return !self.getApplicationSettings().isDisableEncryption();
    }

    /** @return {@code true} if per-file upload passwords are allowed */
    public boolean isUploadPasswordEnabled() {
        return !self.getApplicationSettings().isDisableUploadPassword();
    }

    /** @return {@code true} if in-browser file preview is enabled */
    public boolean isPreviewEnabled() {
        return !self.getApplicationSettings().isDisablePreview();
    }

    /** @return {@code true} if EXIF/metadata stripping is enabled on image uploads */
    public boolean isMetadataStrippingEnabled() {
        return self.getApplicationSettings().isMetadataStrippingEnabled();
    }

    /** @return maximum file size in bytes eligible for browser preview */
    public long getMaxPreviewSizeBytes() {
        return self.getApplicationSettings().getMaxPreviewSizeBytes();
    }

    /** @return identifier of the page shown at {@code /} (e.g. {@code "upload"} or {@code "list"}) */
    public String getDefaultHomePage() {
        return self.getApplicationSettings().getDefaultHomePage();
    }

    /** @return {@code true} if only admins can mark files as "keep indefinitely" */
    public boolean isKeepIndefinitelyAdminOnly() {
        return self.getApplicationSettings().isKeepIndefinitelyAdminOnly();
    }

    /** @return {@code true} if only admins can hide files from the public list */
    public boolean isHideFromListAdminOnly() {
        return self.getApplicationSettings().isHideFromListAdminOnly();
    }

    /** @return {@code true} if Discord webhook notifications are enabled */
    public boolean isDiscordWebhookEnabled() {
        return self.getApplicationSettings().isDiscordWebhookEnabled();
    }

    /** @return configured Discord webhook URL */
    public String getDiscordWebhookUrl() {
        return self.getApplicationSettings().getDiscordWebhookUrl();
    }

    /** @return {@code true} if email notifications are enabled */
    public boolean isEmailNotificationsEnabled() {
        return self.getApplicationSettings().isEmailNotificationsEnabled();
    }

    /** @return the "From" address used for outgoing email notifications */
    public String getEmailFrom() {
        return self.getApplicationSettings().getEmailFrom();
    }

    /** @return comma-separated list of email notification recipients */
    public String getEmailTo() {
        return self.getApplicationSettings().getEmailTo();
    }

    /** @return SMTP host for outgoing mail */
    public String getSmtpHost() {
        return self.getApplicationSettings().getSmtpHost();
    }

    /** @return SMTP port for outgoing mail */
    public Integer getSmtpPort() {
        return self.getApplicationSettings().getSmtpPort();
    }

    /** @return SMTP authentication username */
    public String getSmtpUsername() {
        return self.getApplicationSettings().getSmtpUsername();
    }

    /** @return SMTP authentication password */
    public String getSmtpPassword() {
        return self.getApplicationSettings().getSmtpPassword();
    }

    /** @return {@code true} if STARTTLS should be used for SMTP */
    public boolean isSmtpUseTls() {
        return self.getApplicationSettings().isSmtpUseTls();
    }

    /** @return {@code true} if SSL/TLS wrapping should be used for SMTP */
    public boolean isSmtpUseSsl() {
        return self.getApplicationSettings().isSmtpUseSsl();
    }

    /** @return {@code true} if notification batching is enabled */
    public boolean isNotificationBatchEnabled() {
        return self.getApplicationSettings().isNotificationBatchEnabled();
    }

    /**
     * Returns {@code true} if simplified share links are enabled.
     * Simplified links are automatically disabled when share links are globally disabled.
     */
    public boolean isSimplifiedShareLinksEnabled() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isSimplifiedShareLinks() && !s.isShareLinksDisabled();
    }

    /** @return {@code true} if share link generation is globally disabled */
    public boolean isShareLinksDisabled() {
        return self.getApplicationSettings().isShareLinksDisabled();
    }

    /** @return {@code true} if the pastebin feature is enabled */
    public boolean isPastebinEnabled() {
        return self.getApplicationSettings().isPastebinEnabled();
    }

    /** @return notification batch flush interval in minutes */
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
        Path candidate = brandingDir.resolve(fileName);
        if (Files.exists(candidate)) {
            return "/branding/" + candidate.getFileName();
        }
        return "/images/favicon.png";
    }
}
