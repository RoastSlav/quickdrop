package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
public class ShareTokenEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "file_id", nullable = false)
    public FileEntity file;
    public String shareToken;
    public LocalDate tokenExpirationDate;
    public Integer numberOfAllowedDownloads;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public ShareTokenEntity() {
    }

    public ShareTokenEntity(String token, FileEntity file, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        this.shareToken = token;
        this.file = file;
        this.tokenExpirationDate = tokenExpirationDate;
        this.numberOfAllowedDownloads = numberOfDownloads;
    }
}
