package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;

public class ApplicationSettingsViewModel {
    private Long id;

    private long maxFileSize;
    private long maxFileLifeTime;
    private String fileStoragePath;
    private String logStoragePath;
    private String fileDeletionCron;
    private boolean appPasswordEnabled;
    private String appPassword;
    private long sessionLifeTime;

    public ApplicationSettingsViewModel() {
    }

    public ApplicationSettingsViewModel(ApplicationSettingsEntity settings) {
        this.id = settings.getId();
        this.maxFileSize = settings.getMaxFileSize();
        this.maxFileLifeTime = settings.getMaxFileLifeTime();
        this.fileStoragePath = settings.getFileStoragePath();
        this.logStoragePath = settings.getLogStoragePath();
        this.fileDeletionCron = settings.getFileDeletionCron();
        this.appPasswordEnabled = settings.isAppPasswordEnabled();
        this.sessionLifeTime = settings.getSessionLifetime();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public long getMaxFileLifeTime() {
        return maxFileLifeTime;
    }

    public void setMaxFileLifeTime(long maxFileLifeTime) {
        this.maxFileLifeTime = maxFileLifeTime;
    }

    public String getFileStoragePath() {
        return fileStoragePath;
    }

    public void setFileStoragePath(String fileStoragePath) {
        this.fileStoragePath = fileStoragePath;
    }

    public String getLogStoragePath() {
        return logStoragePath;
    }

    public void setLogStoragePath(String logStoragePath) {
        this.logStoragePath = logStoragePath;
    }

    public String getFileDeletionCron() {
        return fileDeletionCron;
    }

    public void setFileDeletionCron(String fileDeletionCron) {
        this.fileDeletionCron = fileDeletionCron;
    }

    public boolean isAppPasswordEnabled() {
        return appPasswordEnabled;
    }

    public void setAppPasswordEnabled(boolean appPasswordEnabled) {
        this.appPasswordEnabled = appPasswordEnabled;
    }

    public String getAppPassword() {
        return appPassword;
    }

    public void setAppPassword(String appPassword) {
        this.appPassword = appPassword;
    }

    public long getSessionLifeTime() {
        return sessionLifeTime;
    }

    public void setSessionLifeTime(long sessionLifeTime) {
        this.sessionLifeTime = sessionLifeTime;
    }
}
