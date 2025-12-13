package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;
import org.rostislav.quickdrop.model.FileHistoryType;

import java.time.LocalDateTime;

@Entity
public class FileHistoryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @Enumerated(EnumType.STRING)
    private FileHistoryType eventType;

    private LocalDateTime eventDate;

    private String ipAddress;

    @Column(columnDefinition = "TEXT")
    private String userAgent;

    public FileHistoryLog() {
        this.eventDate = LocalDateTime.now();
    }

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
