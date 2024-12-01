package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.ApplicationSettingsEntity;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.stereotype.Service;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

@Service
public class ApplicationSettingsService {
    private final ApplicationSettingsRepository applicationSettingsRepository;
    private final ContextRefresher contextRefresher;
    private ApplicationSettingsEntity applicationSettings;

    public ApplicationSettingsService(ApplicationSettingsRepository applicationSettingsRepository, @Qualifier("configDataContextRefresher") ContextRefresher contextRefresher) {
        this.contextRefresher = contextRefresher;
        this.applicationSettingsRepository = applicationSettingsRepository;

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
            return settings;
        });
    }

    public ApplicationSettingsEntity getApplicationSettings() {
        return applicationSettings;
    }

    public void updateApplicationSettings(ApplicationSettingsEntity settings) {
        ApplicationSettingsEntity applicationSettingsEntity = applicationSettingsRepository.findById(1L).orElseThrow();
        applicationSettingsEntity.setMaxFileSize(settings.getMaxFileSize());
        applicationSettingsEntity.setMaxFileLifeTime(settings.getMaxFileLifeTime());
        applicationSettingsEntity.setFileStoragePath(settings.getFileStoragePath());
        applicationSettingsEntity.setLogStoragePath(settings.getLogStoragePath());
        applicationSettingsEntity.setFileDeletionCron(settings.getFileDeletionCron());
        applicationSettingsEntity.setAppPasswordEnabled(settings.isAppPasswordEnabled());


        applicationSettingsRepository.save(applicationSettingsEntity);
        this.applicationSettings = applicationSettingsEntity;
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
}
