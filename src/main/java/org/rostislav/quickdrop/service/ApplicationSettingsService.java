package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

@Service
public class ApplicationSettingsService {
    private final ApplicationSettingsRepository applicationSettingsRepository;
    private final ContextRefresher contextRefresher;
    private final ScheduleService scheduleService;
    private ApplicationSettingsEntity applicationSettings;

    public ApplicationSettingsService(ApplicationSettingsRepository applicationSettingsRepository, @Qualifier("configDataContextRefresher") ContextRefresher contextRefresher, ScheduleService scheduleService) {
        this.contextRefresher = contextRefresher;
        this.applicationSettingsRepository = applicationSettingsRepository;
        this.scheduleService = scheduleService;

        this.applicationSettings = applicationSettingsRepository.findById(1L).orElseGet(() -> {
            ApplicationSettingsEntity settings = new ApplicationSettingsEntity();
            settings.setMaxFileSize(1024L * 1024L * 1024L);
            settings.setMaxFileLifeTime(30L);
            settings.setFileStoragePath("files");
            settings.setLogStoragePath("logs");
            settings.setFileDeletionCron("0 0 2 * * *");
            settings.setAppPasswordEnabled(false);
            settings.setAppPasswordHash("");
            settings.setAdminPasswordHash("");
            settings.setSessionLifetime(30);
            settings.setFileListPageEnabled(true);
            settings.setAdminDashboardButtonEnabled(true);
            settings.setDisableEncryption(false);
            settings.setDisableUploadPassword(false);
            settings.setDefaultHomePage("upload");
            settings.setKeepIndefinitelyAdminOnly(false);
            settings.setDiscordWebhookEnabled(false);
            settings.setDiscordWebhookUrl("");
            settings.setEmailNotificationsEnabled(false);
            settings.setEmailFrom("");
            settings.setEmailTo("");
            settings.setSmtpHost("");
            settings.setSmtpPort(587);
            settings.setSmtpUsername("");
            settings.setSmtpPassword("");
            settings.setSmtpUseTls(true);
            settings.setNotificationBatchEnabled(false);
            settings.setNotificationBatchMinutes(5);
            settings = applicationSettingsRepository.save(settings);
            scheduleService.updateSchedule(settings.getFileDeletionCron(), settings.getMaxFileLifeTime());
            return settings;
        });
    }

    public ApplicationSettingsEntity getApplicationSettings() {
        return applicationSettings;
    }

    public void updateApplicationSettings(ApplicationSettingsViewModel settings, String appPassword) {
        ApplicationSettingsEntity applicationSettingsEntity = applicationSettingsRepository.findById(1L).orElseThrow();
        applicationSettingsEntity.setMaxFileSize(settings.getMaxFileSize());
        applicationSettingsEntity.setMaxFileLifeTime(settings.getMaxFileLifeTime());
        applicationSettingsEntity.setFileStoragePath(settings.getFileStoragePath());
        applicationSettingsEntity.setLogStoragePath(settings.getLogStoragePath());
        applicationSettingsEntity.setFileDeletionCron(settings.getFileDeletionCron());
        applicationSettingsEntity.setSessionLifetime(settings.getSessionLifeTime());
        applicationSettingsEntity.setFileListPageEnabled(settings.isFileListPageEnabled());
        applicationSettingsEntity.setAdminDashboardButtonEnabled(settings.isAdminDashboardButtonEnabled());
        applicationSettingsEntity.setDisableUploadPassword(settings.isDisableUploadPassword());
        // If upload passwords are disabled, force encryption off and lock the toggle
        if (settings.isDisableUploadPassword()) {
            applicationSettingsEntity.setDisableEncryption(true);
        } else {
            applicationSettingsEntity.setDisableEncryption(settings.isEncryptionDisabled());
        }
        applicationSettingsEntity.setDefaultHomePage(settings.getDefaultHomePage());
        applicationSettingsEntity.setKeepIndefinitelyAdminOnly(settings.isKeepIndefinitelyAdminOnly());
        applicationSettingsEntity.setDiscordWebhookEnabled(settings.isDiscordWebhookEnabled());
        applicationSettingsEntity.setDiscordWebhookUrl(settings.getDiscordWebhookUrl());
        applicationSettingsEntity.setEmailNotificationsEnabled(settings.isEmailNotificationsEnabled());
        applicationSettingsEntity.setEmailFrom(settings.getEmailFrom());
        applicationSettingsEntity.setEmailTo(settings.getEmailTo());
        applicationSettingsEntity.setSmtpHost(settings.getSmtpHost());
        applicationSettingsEntity.setSmtpPort(settings.getSmtpPort());
        applicationSettingsEntity.setSmtpUsername(settings.getSmtpUsername());
        if (settings.getSmtpPassword() != null && !settings.getSmtpPassword().isBlank()) {
            applicationSettingsEntity.setSmtpPassword(settings.getSmtpPassword());
        }
        applicationSettingsEntity.setSmtpUseTls(settings.isSmtpUseTls());
        applicationSettingsEntity.setNotificationBatchEnabled(settings.isNotificationBatchEnabled());
        Integer existingBatchMinutes = applicationSettingsEntity.getNotificationBatchMinutes();
        Integer requestedBatchMinutes = settings.getNotificationBatchMinutes();
        if (requestedBatchMinutes != null) {
            applicationSettingsEntity.setNotificationBatchMinutes(requestedBatchMinutes);
        } else if (existingBatchMinutes != null) {
            applicationSettingsEntity.setNotificationBatchMinutes(existingBatchMinutes);
        }

        if (appPassword != null && !appPassword.isEmpty()) {
            applicationSettingsEntity.setAppPasswordEnabled(settings.isAppPasswordEnabled());
            applicationSettingsEntity.setAppPasswordHash(BCrypt.hashpw(appPassword, BCrypt.gensalt()));
        } else if (!settings.isAppPasswordEnabled()) {
            applicationSettingsEntity.setAppPasswordEnabled(false);
        }

        applicationSettingsRepository.save(applicationSettingsEntity);
        this.applicationSettings = applicationSettingsEntity;

        scheduleService.updateSchedule(applicationSettingsEntity.getFileDeletionCron(), applicationSettingsEntity.getMaxFileLifeTime());
        contextRefresher.refresh();
    }

    public long getMaxFileSize() {
        return applicationSettings.getMaxFileSize();
    }

    public String getFormattedMaxFileSize() {
        return formatFileSize(applicationSettings.getMaxFileSize());
    }

    public long getMaxFileLifeTime() {
        return applicationSettings.getMaxFileLifeTime();
    }

    public String getFileStoragePath() {
        return applicationSettings.getFileStoragePath();
    }

    public String getLogStoragePath() {
        return applicationSettings.getLogStoragePath();
    }

    public String getFileDeletionCron() {
        return applicationSettings.getFileDeletionCron();
    }

    public boolean isAppPasswordEnabled() {
        return applicationSettings.isAppPasswordEnabled();
    }

    public String getAppPasswordHash() {
        return applicationSettings.getAppPasswordHash();
    }

    public String getAdminPasswordHash() {
        return applicationSettings.getAdminPasswordHash();
    }

    public boolean isFileListPageEnabled() {
        return applicationSettings.isFileListPageEnabled();
    }

    public boolean isAdminPasswordSet() {
        return !applicationSettings.getAdminPasswordHash().isEmpty();
    }

    public void setAdminPassword(String adminPassword) {
        ApplicationSettingsEntity applicationSettingsEntity = applicationSettingsRepository.findById(1L).orElseThrow();
        applicationSettingsEntity.setAdminPasswordHash(BCrypt.hashpw(adminPassword, BCrypt.gensalt()));
        applicationSettingsRepository.save(applicationSettingsEntity);
        this.applicationSettings = applicationSettingsEntity;
    }

    public boolean checkForAdminPassword(HttpServletRequest request) {
        String password = (String) request.getSession().getAttribute("adminPassword");
        String adminPasswordHash = getAdminPasswordHash();
        return password != null && password.equals(adminPasswordHash);
    }

    public long getSessionLifetime() {
        return applicationSettings.getSessionLifetime();
    }

    public boolean isAdminDashboardButtonEnabled() {
        return applicationSettings.isAdminDashboardButtonEnabled();
    }

    public boolean isEncryptionEnabled() {
        return !applicationSettings.isDisableEncryption();
    }

    public boolean isUploadPasswordEnabled() {
        return !applicationSettings.isDisableUploadPassword();
    }

    public String getDefaultHomePage() {
        return applicationSettings.getDefaultHomePage();
    }

    public boolean isKeepIndefinitelyAdminOnly() {
        return applicationSettings.isKeepIndefinitelyAdminOnly();
    }

    public boolean isDiscordWebhookEnabled() {
        return applicationSettings.isDiscordWebhookEnabled();
    }

    public String getDiscordWebhookUrl() {
        return applicationSettings.getDiscordWebhookUrl();
    }

    public boolean isEmailNotificationsEnabled() {
        return applicationSettings.isEmailNotificationsEnabled();
    }

    public String getEmailFrom() {
        return applicationSettings.getEmailFrom();
    }

    public String getEmailTo() {
        return applicationSettings.getEmailTo();
    }

    public String getSmtpHost() {
        return applicationSettings.getSmtpHost();
    }

    public Integer getSmtpPort() {
        return applicationSettings.getSmtpPort();
    }

    public String getSmtpUsername() {
        return applicationSettings.getSmtpUsername();
    }

    public String getSmtpPassword() {
        return applicationSettings.getSmtpPassword();
    }

    public boolean isSmtpUseTls() {
        return applicationSettings.isSmtpUseTls();
    }

    public boolean isNotificationBatchEnabled() {
        return applicationSettings.isNotificationBatchEnabled();
    }

    public Integer getNotificationBatchMinutes() {
        return applicationSettings.getNotificationBatchMinutes();
    }
}
