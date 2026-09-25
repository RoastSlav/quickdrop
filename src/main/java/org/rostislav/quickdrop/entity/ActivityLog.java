package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;
import org.rostislav.quickdrop.model.EventType;

import java.time.LocalDateTime;

/**
 * Audit record for one {@link EventType} event, tied to an {@link Upload}, a short link,
 * or neither (admin/system events). Drives the analytics dashboard and per-file history page.
 *
 * <p>Rows are kept even after the parent file is soft-deleted, so history stays viewable.
 */
@Entity
@Table(name = "activity_log", indexes = {
    @Index(name = "idx_activity_log_event_type", columnList = "event_type"),
    @Index(name = "idx_activity_log_event_date", columnList = "event_date"),
    @Index(name = "idx_activity_log_file_date", columnList = "file_id,event_date")
})
public class ActivityLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for admin/system events. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = true)
    private Upload file;

    /** Null for events not tied to a short link. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "short_link_id", nullable = true)
    private ShortLink shortLink;

    @Enumerated(EnumType.STRING)
    private EventType eventType;

    private LocalDateTime eventDate;

    /** Resolved from {@code X-Forwarded-For} or {@code X-Real-IP}. */
    private String ipAddress;

    /** TEXT column since some User-Agent strings exceed a default VARCHAR limit. */
    @Column(columnDefinition = "TEXT")
    private String userAgent;

    /**
     * Free-text specifics for events the {@link EventType} alone doesn't identify, such as
     * which reputation feed refreshed. {@code null} when there is nothing to add.
     */
    @Column(columnDefinition = "TEXT")
    private String detail;

    public ActivityLog() {
        this.eventDate = LocalDateTime.now();
    }

    public ActivityLog(Upload file, EventType eventType, String ipAddress, String userAgent) {
        this.file = file;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.eventDate = LocalDateTime.now();
    }

    /**
     * For events not tied to a file: {@link EventCategory#ADMIN} (login, logout, settings
     * change) and {@link EventCategory#SYSTEM} (startup/shutdown). ipAddress/userAgent are
     * null for system events.
     */
    public ActivityLog(EventType eventType, String ipAddress, String userAgent) {
        this.file = null;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.eventDate = LocalDateTime.now();
    }

    /** For {@link EventCategory#SHORTLINK} events; ipAddress/userAgent null if system-triggered. */
    public ActivityLog(ShortLink shortLink, EventType eventType, String ipAddress, String userAgent) {
        this.shortLink = shortLink;
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

    public ShortLink getShortLink() {
        return shortLink;
    }

    public void setShortLink(ShortLink shortLink) {
        this.shortLink = shortLink;
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

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
