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

@Service
public class ApplicationSettingsService {
    private final ApplicationSettingsRepository applicationSettingsRepository;
    private final ContextRefresher contextRefresher;

    @Lazy
    @Autowired
    private ApplicationSettingsService self;

    @Lazy
    @Autowired
    private ScheduleService scheduleService;

    public ApplicationSettingsService(ApplicationSettingsRepository applicationSettingsRepository,
                                      @Qualifier("configDataContextRefresher") ContextRefresher contextRefresher) {
        this.contextRefresher = contextRefresher;
        this.applicationSettingsRepository = applicationSettingsRepository;
    }

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

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ApplicationSettingsEntity settings = applicationSettingsRepository.findById(1L).orElseThrow();
        scheduleService.updateSchedule(settings.getFileDeletionCron(), settings.getMaxFileLifeTime());
    }

    @Cacheable("applicationSettings")
    public ApplicationSettingsEntity getApplicationSettings() {
        return applicationSettingsRepository.findById(1L).orElseThrow();
    }

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

    @CacheEvict(value = "applicationSettings", allEntries = true)
    public void setAdminPassword(String adminPassword) {
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

    public long getMaxFileLifeTime() {
        return self.getApplicationSettings().getMaxFileLifeTime();
    }

    public String getFileStoragePath() {
        return self.getApplicationSettings().getFileStoragePath();
    }

    public String getLogStoragePath() {
        return self.getApplicationSettings().getLogStoragePath();
    }

    public String getFileDeletionCron() {
        return self.getApplicationSettings().getFileDeletionCron();
    }

    public boolean isAppPasswordEnabled() {
        return self.getApplicationSettings().isAppPasswordEnabled();
    }

    public String getAppPasswordHash() {
        return self.getApplicationSettings().getAppPasswordHash();
    }

    public String getAdminPasswordHash() {
        return self.getApplicationSettings().getAdminPasswordHash();
    }

    public boolean isFileListPageEnabled() {
        return self.getApplicationSettings().isFileListPageEnabled();
    }

    public boolean isAdminPasswordSet() {
        return !self.getApplicationSettings().getAdminPasswordHash().isEmpty();
    }

    public long getSessionLifetime() {
        return self.getApplicationSettings().getSessionLifetime();
    }

    public boolean isAdminDashboardButtonEnabled() {
        return self.getApplicationSettings().isAdminDashboardButtonEnabled();
    }

    public boolean isEncryptionEnabled() {
        return !self.getApplicationSettings().isDisableEncryption();
    }

    public boolean isUploadPasswordEnabled() {
        return !self.getApplicationSettings().isDisableUploadPassword();
    }

    public boolean isPreviewEnabled() {
        return !self.getApplicationSettings().isDisablePreview();
    }

    public boolean isMetadataStrippingEnabled() {
        return self.getApplicationSettings().isMetadataStrippingEnabled();
    }

    public long getMaxPreviewSizeBytes() {
        return self.getApplicationSettings().getMaxPreviewSizeBytes();
    }

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

    public boolean isSmtpUseTls() {
        return self.getApplicationSettings().isSmtpUseTls();
    }

    public boolean isSmtpUseSsl() {
        return self.getApplicationSettings().isSmtpUseSsl();
    }

    public boolean isNotificationBatchEnabled() {
        return self.getApplicationSettings().isNotificationBatchEnabled();
    }

    public boolean isSimplifiedShareLinksEnabled() {
        ApplicationSettingsEntity s = self.getApplicationSettings();
        return s.isSimplifiedShareLinks() && !s.isShareLinksDisabled();
    }

    public boolean isShareLinksDisabled() {
        return self.getApplicationSettings().isShareLinksDisabled();
    }

    public boolean isPastebinEnabled() {
        return self.getApplicationSettings().isPastebinEnabled();
    }

    public Integer getNotificationBatchMinutes() {
        return self.getApplicationSettings().getNotificationBatchMinutes();
    }

    public String getAppName() {
        String name = self.getApplicationSettings().getAppName();
        return (name == null || name.isBlank()) ? "QuickDrop" : name;
    }

    public String getDefaultLanguage() {
        String lang = self.getApplicationSettings().getDefaultLanguage();
        return (lang == null || lang.isBlank()) ? "en" : lang;
    }

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
