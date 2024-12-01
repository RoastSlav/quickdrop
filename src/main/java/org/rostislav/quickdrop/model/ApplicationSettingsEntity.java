package org.rostislav.quickdrop.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

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

    public ApplicationSettingsEntity() {
    }

    public ApplicationSettingsEntity(ApplicationSettingsEntity settings) {
        this.id = settings.id;
        this.maxFileSize = settings.maxFileSize;
        this.maxFileLifeTime = settings.maxFileLifeTime;
        this.fileStoragePath = settings.fileStoragePath;
        this.logStoragePath = settings.logStoragePath;
        this.fileDeletionCron = settings.fileDeletionCron;
        this.appPasswordEnabled = settings.appPasswordEnabled;
        this.appPasswordHash = settings.appPasswordHash;
        this.adminPasswordHash = settings.adminPasswordHash;
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
}
