package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
public class ShareTokenEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "file_id", nullable = false)
    public FileEntity file;

    @Column(name = "share_token", nullable = false, unique = true, length = 10)
    public String shareToken;

    @Column(name = "token_expiration_date")
    public LocalDate tokenExpirationDate;

    @Column(name = "number_of_allowed_downloads")
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
