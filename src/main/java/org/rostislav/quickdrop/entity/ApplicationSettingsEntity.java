package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;

@Entity
public class ApplicationSettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long maxFileSize;
    private long maxFileLifeTime;
    private String fileStoragePath;
    private String logStoragePath;
    private String fileDeletionCron;
    private boolean appPasswordEnabled;
    private String appPasswordHash;
    private String adminPasswordHash;
    @Column(name = "sessionLifetime", nullable = false, columnDefinition = "BIGINT DEFAULT 30")
    private long sessionLifetime;
    @Column(name = "isFileListPageEnabled", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean isFileListPageEnabled;
    private boolean isAdminDashboardButtonEnabled;

    public ApplicationSettingsEntity() {
    }

    public ApplicationSettingsEntity(ApplicationSettingsViewModel settings) {
        this.id = settings.getId();
        this.maxFileSize = settings.getMaxFileSize();
        this.maxFileLifeTime = settings.getMaxFileLifeTime();
        this.fileStoragePath = settings.getFileStoragePath();
        this.logStoragePath = settings.getLogStoragePath();
        this.fileDeletionCron = settings.getFileDeletionCron();
        this.appPasswordEnabled = settings.isAppPasswordEnabled();
        this.isFileListPageEnabled = settings.isFileListPageEnabled();
        this.isAdminDashboardButtonEnabled = settings.isAdminDashboardButtonEnabled();
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

    public String getAppPasswordHash() {
        return appPasswordHash;
    }

    public void setAppPasswordHash(String appPasswordHash) {
        this.appPasswordHash = appPasswordHash;
    }

    public String getAdminPasswordHash() {
        return adminPasswordHash;
    }

    public void setAdminPasswordHash(String adminPasswordHash) {
        this.adminPasswordHash = adminPasswordHash;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getSessionLifetime() {
        return sessionLifetime;
    }

    public void setSessionLifetime(long sessionLifetime) {
        this.sessionLifetime = sessionLifetime;
    }

    public boolean isFileListPageEnabled() {
        return isFileListPageEnabled;
    }

    public void setFileListPageEnabled(boolean fileListPageEnabled) {
        isFileListPageEnabled = fileListPageEnabled;
    }

    public boolean isAdminDashboardButtonEnabled() {
        return isAdminDashboardButtonEnabled;
    }

    public void setAdminDashboardButtonEnabled(boolean adminDashboardButtonEnabled) {
        isAdminDashboardButtonEnabled = adminDashboardButtonEnabled;
    }
}
