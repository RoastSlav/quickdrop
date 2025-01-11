package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.DownloadLog;
import org.rostislav.quickdrop.entity.FileRenewalLog;

import java.time.LocalDateTime;

public class FileActionLogDTO {
    private String actionType; // "Download" or "Lifetime Renewed"
    private LocalDateTime actionDate;
    private String ipAddress;
    private String userAgent;

    public FileActionLogDTO(String actionType, LocalDateTime actionDate, String ipAddress, String userAgent) {
        this.actionType = actionType;
        this.actionDate = actionDate;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public FileActionLogDTO(DownloadLog downloadLog) {
        this.actionType = "Download";
        this.actionDate = downloadLog.getDownloadDate();
        this.ipAddress = downloadLog.getDownloaderIp();
        this.userAgent = downloadLog.getUserAgent();
    }

    public FileActionLogDTO(FileRenewalLog renewalLog) {
        this.actionType = "Lifetime Renewed";
        this.actionDate = renewalLog.getActionDate();
        this.ipAddress = renewalLog.getIpAddress();
        this.userAgent = renewalLog.getUserAgent();
    }

    // Getters and setters
    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
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
}
