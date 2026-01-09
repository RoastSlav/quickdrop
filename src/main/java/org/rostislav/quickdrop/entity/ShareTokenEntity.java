package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "share_token_entity", indexes = {
    @Index(name = "idx_share_token_public_id", columnList = "public_id")
})
public class ShareTokenEntity {
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "file_id", nullable = false)
    public FileEntity file;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_token", nullable = false, unique = true, length = 64)
    public String shareToken;

    @Column(name = "public_id", length = 16)
    public String publicId;

    @Column(name = "wrapped_dek", length = 512)
    public String wrappedDek;

    @Column(name = "wrap_nonce", length = 128)
    public String wrapNonce;

    @Column(name = "secret_hash", length = 128)
    public String secretHash;

    @Column(name = "encryption_version")
    public Integer encryptionVersion;

    @Column(name = "token_mode", length = 32)
    public String tokenMode;

    @Column(name = "token_expiration_date")
    public LocalDate tokenExpirationDate;

    @Column(name = "number_of_allowed_downloads")
    public Integer numberOfAllowedDownloads;

    public ShareTokenEntity() {
    }

    public ShareTokenEntity(String token, FileEntity file, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        this.shareToken = token;
        this.file = file;
        this.tokenExpirationDate = tokenExpirationDate;
        this.numberOfAllowedDownloads = numberOfDownloads;
    }
}
