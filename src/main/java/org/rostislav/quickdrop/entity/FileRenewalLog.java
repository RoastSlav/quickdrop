package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
public class FileRenewalLog {
    @Id
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;
    private LocalDateTime actionDate;
    private String ipAddress;
    private String userAgent;

    public FileRenewalLog() {
        this.actionDate = LocalDateTime.now();
    }

    public FileRenewalLog(FileEntity file, String ipAddress, String userAgent) {
        this.file = file;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.actionDate = LocalDateTime.now();
    }

    public FileEntity getFile() {
        return file;
    }

    public void setFile(FileEntity file) {
        this.file = file;
    }

    public LocalDateTime getActionDate() {
        return actionDate;
    }

    public void setActionDate(LocalDateTime actionDate) {
        this.actionDate = actionDate;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}