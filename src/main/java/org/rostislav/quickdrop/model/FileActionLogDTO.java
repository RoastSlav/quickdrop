package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.FileHistoryLog;

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

    public FileActionLogDTO(FileHistoryLog historyLog) {
        this.actionType = mapType(historyLog.getEventType());
        this.actionDate = historyLog.getEventDate();
        this.ipAddress = historyLog.getIpAddress();
        this.userAgent = historyLog.getUserAgent();
    }

    private String mapType(FileHistoryType type) {
        return switch (type) {
            case DOWNLOAD -> "download";
            case RENEWAL -> "renewal";
            case UPLOAD -> "upload";
            case DELETION -> "deletion";
            case PASTE_CREATE -> "paste_create";
            case PASTE_VIEW -> "paste_view";
            case PASTE_EDIT -> "paste_edit";
        };
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
