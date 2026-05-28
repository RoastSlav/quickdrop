package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;
import org.rostislav.quickdrop.model.EventType;

import java.time.LocalDateTime;

/**
 * Audit record capturing a single event in the lifecycle of an {@link Upload}
 * (file or paste) or of the application itself.
 *
 * <p>A new row is appended for every {@link EventType} event (upload, download,
 * renewal, deletion, paste operations, admin actions, application lifecycle). These
 * records drive the analytics dashboard and the per-file history page.
 *
 * <p>File-associated rows are <em>not</em> deleted when the parent file is soft-deleted;
 * they are retained so the activity log can still display the full history of a file
 * after deletion.
 *
 */
@Entity
public class ActivityLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The upload (file or paste) this event is associated with, or {@code null}
     * for admin/system events.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = true)
    private Upload file;

    /**
     * Category of the event.
     */
    @Enumerated(EnumType.STRING)
    private EventType eventType;

    /**
     * Timestamp when this record was created (set in the no-arg constructor).
     */
    private LocalDateTime eventDate;

    /**
     * IP address of the requester, resolved from {@code X-Forwarded-For} or {@code X-Real-IP}.
     */
    private String ipAddress;

    /**
     * {@code User-Agent} header from the HTTP request, stored as TEXT to accommodate long values.
     */
    @Column(columnDefinition = "TEXT")
    private String userAgent;

    /**
     * Creates an empty log entry and stamps {@link #eventDate} to now.
     */
    public ActivityLog() {
        this.eventDate = LocalDateTime.now();
    }

    /**
     * Convenience constructor for the common case where all fields are known upfront.
     *
     * @param file      the upload (file or paste) the event occurred on
     * @param eventType category of the event
     * @param ipAddress requester IP address
     * @param userAgent requester User-Agent header value
     */
    public ActivityLog(Upload file, EventType eventType, String ipAddress, String userAgent) {
        this.file = file;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.eventDate = LocalDateTime.now();
    }

    /**
     * Convenience constructor for events that are not associated with a specific file —
     * covers {@link EventCategory#ADMIN} events (login, logout, settings change) and
     * {@link EventCategory#SYSTEM} events (application startup / shutdown).
     *
     * @param eventType category of the event
     * @param ipAddress requester IP address, or {@code null} for system events
     * @param userAgent requester User-Agent header value, or {@code null} for system events
     */
    public ActivityLog(EventType eventType, String ipAddress, String userAgent) {
        this.file = null;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.eventDate = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Upload getFile() {
        return file;
    }

    public void setFile(Upload file) {
        this.file = file;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
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
