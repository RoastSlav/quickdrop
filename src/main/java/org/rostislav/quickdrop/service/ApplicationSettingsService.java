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
    private ApplicationSettingsEntity applicationSettings;
    private final ScheduleService scheduleService;

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
        applicationSettingsEntity.setAppPasswordEnabled(settings.isAppPasswordEnabled());

        if (settings.isAppPasswordEnabled()) {
            applicationSettingsEntity.setAppPasswordHash(BCrypt.hashpw(appPassword, BCrypt.gensalt()));
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
}
