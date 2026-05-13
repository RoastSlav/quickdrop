package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.FileHistoryLog;

import java.time.LocalDateTime;

/**
 * Data-transfer object representing a single entry in a file's action history,
 * shown on the per-file history page.
 *
 * <p>Created from a {@link FileHistoryLog} via the {@link #FileActionLogDTO(FileHistoryLog)}
 * constructor which maps the enum type to a human-readable lowercase string.
 */
public class FileActionLogDTO {
    /**
     * Lowercase action label (e.g. "download", "renewal", "upload").
     */
    private String actionType;
    private LocalDateTime actionDate;
    private String ipAddress;
    private String userAgent;

    /**
     * @param actionType the action label string
     * @param actionDate when the event occurred
     * @param ipAddress  requester IP address
     * @param userAgent  requester User-Agent header value
     */
    public FileActionLogDTO(String actionType, LocalDateTime actionDate, String ipAddress, String userAgent) {
        this.actionType = actionType;
        this.actionDate = actionDate;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    /**
     * Builds a DTO from a raw history log row, mapping the {@link FileHistoryType} enum
     * to the lowercase action label shown in the UI.
     *
     * @param historyLog the audit log entry
     */
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
