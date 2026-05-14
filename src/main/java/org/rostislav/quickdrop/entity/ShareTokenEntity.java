package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A short-lived or limited-use token that grants download access to a specific file
 * without requiring the file's password.
 *
 * <p>Tokens are generated via
 * {@link org.rostislav.quickdrop.service.FileService#generateShareToken} and served
 * under the {@code /share/{token}} route. A token is considered valid when:
 * <ul>
 *   <li>{@link #tokenExpirationDate} is {@code null} or is not yet in the past, and</li>
 *   <li>{@link #numberOfAllowedDownloads} is {@code null} (unlimited) or is greater than zero.</li>
 * </ul>
 * Expired / exhausted tokens are removed by the nightly
 * {@link org.rostislav.quickdrop.service.ScheduleService#cleanShareTokens()} job.
 */
@Entity
public class ShareTokenEntity {
    /**
     * The file this token grants access to.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "file_id", nullable = false)
    public FileEntity file;
    /** The URL-safe token string (up to 10 characters, unique). */
    @Column(name = "share_token", nullable = false, unique = true, length = 10)
    public String shareToken;
    /** Optional expiry date; {@code null} means the token never expires by date. */
    @Column(name = "token_expiration_date")
    public LocalDate tokenExpirationDate;
    /**
     * Remaining download allowance; decremented on each use.
     * {@code null} means unlimited downloads.
     */
    @Column(name = "number_of_allowed_downloads")
    public Integer numberOfAllowedDownloads;
    /**
     * BCrypt hash of the randomly generated share key embedded in the share URL.
     * {@code null} for tokens created before this feature or for non-encrypted files.
     * When non-null, the sidecar at {@code {uuid}-share-{shareToken}} is AES-encrypted
     * under the share key and the key must be verified before streaming.
     */
    @Column(name = "share_key_hash")
    public String shareKeyHash;
    /**
     * Timestamp when this token was created. Used for display ordering on the admin
     * share-links page. {@code null} for tokens created before this column was added.
     */
    @Column(name = "created_at")
    public LocalDateTime createdAt;
    /**
     * Whether the re-encrypted sidecar file is ready for download.
     * Always {@code true} for non-encrypted files (no sidecar needed).
     * Set to {@code false} when sidecar encryption is submitted as a background task,
     * and flipped to {@code true} once the task completes.
     */
    @Column(name = "sidecar_ready", nullable = false)
    public boolean sidecarReady = true;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * @param token               the short share token string
     * @param file                the file being shared
     * @param tokenExpirationDate optional expiry date ({@code null} = no expiry)
     * @param numberOfDownloads   optional download limit ({@code null} = unlimited)
     */
    public ShareTokenEntity(String token, FileEntity file, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        this.shareToken = token;
        this.file = file;
        this.tokenExpirationDate = tokenExpirationDate;
        this.numberOfAllowedDownloads = numberOfDownloads;
        this.createdAt = LocalDateTime.now();
    }

    public ShareTokenEntity() {
    }

    public Long getId() {
        return id;
    }
}
