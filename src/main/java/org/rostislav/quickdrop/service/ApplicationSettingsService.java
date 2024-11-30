package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.ApplicationSettingsEntity;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class ApplicationSettingsService {
    private final ConfigurableApplicationContext applicationContext;
    private final ApplicationSettingsRepository applicationSettingsRepository;
    private ApplicationSettingsEntity applicationSettings;

    public ApplicationSettingsService(ApplicationSettingsRepository applicationSettingsRepository, ApplicationContext applicationContext) {
        this.applicationContext = (ConfigurableApplicationContext) applicationContext;
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
            this.applicationContext.refresh();
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
    }

    public long getMaxFileSize() {
        return applicationSettings.getMaxFileSize();
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
