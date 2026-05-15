package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;
import org.rostislav.quickdrop.model.FileHistoryType;

import java.time.LocalDateTime;

/**
 * Audit record capturing a single event in the lifecycle of a {@link FileEntity}.
 *
 * <p>A new row is appended for every {@link FileHistoryType} event (upload, download,
 * renewal, deletion, paste operations). These records drive the analytics dashboard
 * and the per-file history page. Rows are deleted in bulk when the parent file is
 * removed from the database.
 */
@Entity
public class FileHistoryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The file this event is associated with.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    /** Category of the event. */
    @Enumerated(EnumType.STRING)
    private FileHistoryType eventType;

    /** Timestamp when this record was created (set in the no-arg constructor). */
    private LocalDateTime eventDate;

    /** IP address of the requester, resolved from {@code X-Forwarded-For} or {@code X-Real-IP}. */
    private String ipAddress;

    /** {@code User-Agent} header from the HTTP request, stored as TEXT to accommodate long values. */
    @Column(columnDefinition = "TEXT")
    private String userAgent;

    /** Creates an empty log entry and stamps {@link #eventDate} to now. */
    public FileHistoryLog() {
        this.eventDate = LocalDateTime.now();
    }

    /**
     * Convenience constructor for the common case where all fields are known upfront.
     *
     * @param file      the file the event occurred on
     * @param eventType category of the event
     * @param ipAddress requester IP address
     * @param userAgent requester User-Agent header value
     */
    public FileHistoryLog(FileEntity file, FileHistoryType eventType, String ipAddress, String userAgent) {
        this.file = file;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.eventDate = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public FileEntity getFile() {
        return file;
    }

    public void setFile(FileEntity file) {
        this.file = file;
    }

    public FileHistoryType getEventType() {
        return eventType;
    }

    public void setEventType(FileHistoryType eventType) {
        this.eventType = eventType;
    }

    public LocalDateTime getEventDate() {
        return eventDate;
    }

    public void setEventDate(LocalDateTime eventDate) {
        this.eventDate = eventDate;
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
