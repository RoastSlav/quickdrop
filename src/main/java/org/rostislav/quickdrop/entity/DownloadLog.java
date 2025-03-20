package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
public class DownloadLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;
    private String downloaderIp;
    private LocalDateTime downloadDate;
    private String userAgent;

    public DownloadLog() {
    }

    public DownloadLog(FileEntity file, String downloaderIp, String userAgent) {
        this.file = file;
        this.downloaderIp = downloaderIp;
        this.downloadDate = LocalDateTime.now();
        this.userAgent = userAgent;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FileEntity getFile() {
        return file;
    }

    public void setFile(FileEntity file) {
        this.file = file;
    }

    public String getDownloaderIp() {
        return downloaderIp;
    }

    public void setDownloaderIp(String downloaderIp) {
        this.downloaderIp = downloaderIp;
    }

    public LocalDateTime getDownloadDate() {
        return downloadDate;
    }

    public void setDownloadDate(LocalDateTime downloadDate) {
        this.downloadDate = downloadDate;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}