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
        return applicationSettingsRepository.findById(1L).orElseThrow().getMaxFileSize();
    }

    public String getFormattedMaxFileSize() {
        return formatFileSize(getMaxFileSize());
    }

    public long getMaxFileLifeTime() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getMaxFileLifeTime();
    }

    public String getFileStoragePath() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getFileStoragePath();
    }

    public String getLogStoragePath() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getLogStoragePath();
    }

    public String getFileDeletionCron() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getFileDeletionCron();
    }

    public boolean isAppPasswordEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isAppPasswordEnabled();
    }

    public String getAppPasswordHash() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getAppPasswordHash();
    }

    public String getAdminPasswordHash() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getAdminPasswordHash();
    }

    public boolean isFileListPageEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isFileListPageEnabled();
    }

    public boolean isAdminPasswordSet() {
        return !applicationSettingsRepository.findById(1L).orElseThrow().getAdminPasswordHash().isEmpty();
    }

    public long getSessionLifetime() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getSessionLifetime();
    }

    public boolean isAdminDashboardButtonEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isAdminDashboardButtonEnabled();
    }

    public boolean isEncryptionEnabled() {
        return !applicationSettingsRepository.findById(1L).orElseThrow().isDisableEncryption();
    }

    public boolean isUploadPasswordEnabled() {
        return !applicationSettingsRepository.findById(1L).orElseThrow().isDisableUploadPassword();
    }

    public boolean isPreviewEnabled() {
        return !applicationSettingsRepository.findById(1L).orElseThrow().isDisablePreview();
    }

    public boolean isMetadataStrippingEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isMetadataStrippingEnabled();
    }

    public long getMaxPreviewSizeBytes() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getMaxPreviewSizeBytes();
    }

    public String getDefaultHomePage() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getDefaultHomePage();
    }

    public boolean isKeepIndefinitelyAdminOnly() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isKeepIndefinitelyAdminOnly();
    }

    public boolean isHideFromListAdminOnly() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isHideFromListAdminOnly();
    }

    public boolean isDiscordWebhookEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isDiscordWebhookEnabled();
    }

    public String getDiscordWebhookUrl() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getDiscordWebhookUrl();
    }

    public boolean isEmailNotificationsEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isEmailNotificationsEnabled();
    }

    public String getEmailFrom() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getEmailFrom();
    }

    public String getEmailTo() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getEmailTo();
    }

    public String getSmtpHost() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getSmtpHost();
    }

    public Integer getSmtpPort() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getSmtpPort();
    }

    public String getSmtpUsername() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getSmtpUsername();
    }

    public String getSmtpPassword() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getSmtpPassword();
    }

    public boolean isSmtpUseTls() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isSmtpUseTls();
    }

    public boolean isSmtpUseSsl() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isSmtpUseSsl();
    }

    public boolean isNotificationBatchEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isNotificationBatchEnabled();
    }

    public boolean isSimplifiedShareLinksEnabled() {
        ApplicationSettingsEntity s = applicationSettingsRepository.findById(1L).orElseThrow();
        return s.isSimplifiedShareLinks() && !s.isShareLinksDisabled();
    }

    public boolean isShareLinksDisabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isShareLinksDisabled();
    }

    public boolean isPastebinEnabled() {
        return applicationSettingsRepository.findById(1L).orElseThrow().isPastebinEnabled();
    }

    public Integer getNotificationBatchMinutes() {
        return applicationSettingsRepository.findById(1L).orElseThrow().getNotificationBatchMinutes();
    }

    public String getAppName() {
        String name = applicationSettingsRepository.findById(1L).orElseThrow().getAppName();
        return (name == null || name.isBlank()) ? "QuickDrop" : name;
    }

    public String getDefaultLanguage() {
        String lang = applicationSettingsRepository.findById(1L).orElseThrow().getDefaultLanguage();
        return (lang == null || lang.isBlank()) ? "en" : lang;
    }

    public String getLogoPath() {
        String fileName = applicationSettingsRepository.findById(1L).orElseThrow().getLogoFileName();
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
