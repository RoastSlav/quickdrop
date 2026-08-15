package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A short-lived or limited-use link that grants download access to a specific file or
 * paste without requiring the upload's password.
 *
 * <p>Renamed from {@code ShareTokenEntity}: this is one {@code target_type} of the general
 * {@link ShortLink} hierarchy. Links are generated via
 * {@link org.rostislav.quickdrop.service.ShortLinkService} and served under both the
 * legacy {@code /share/{token}} route and the general {@code /{prefix}/{code}} resolver.
 * A link is considered valid when:
 * <ul>
 *   <li>{@link #expirationDate} is {@code null} or is not yet in the past, and</li>
 *   <li>{@link #remainingUses} is {@code null} (unlimited) or is greater than zero.</li>
 * </ul>
 * Expired / exhausted links are removed by the nightly
 * {@link org.rostislav.quickdrop.service.ScheduleService#cleanShortLinks()} job.
 */
@Entity
@DiscriminatorValue("UPLOAD")
public class UploadShareLink extends ShortLink {
    /**
     * The upload (file or paste) this link grants access to.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "upload_id")
    public Upload upload;
    /**
     * BCrypt hash of the randomly generated share key embedded in the share URL.
     * {@code null} for links created before this feature or for non-encrypted files.
     * When non-null, the sidecar at {@code {uuid}-share-{code}} is AES-encrypted
     * under the share key and the key must be verified before streaming.
     */
    @Column(name = "share_key_hash")
    public String shareKeyHash;
    /**
     * Whether the re-encrypted sidecar file is ready for download.
     * Always {@code true} for non-encrypted files (no sidecar needed).
     * Set to {@code false} when sidecar encryption is submitted as a background task,
     * and flipped to {@code true} once the task completes.
     */
    @Column(name = "sidecar_ready", nullable = false)
    public boolean sidecarReady = true;

    /**
     * @param code           the short link code string
     * @param upload         the upload being shared
     * @param expirationDate optional expiry date ({@code null} = no expiry)
     * @param remainingUses  optional use limit ({@code null} = unlimited)
     */
    public UploadShareLink(String code, Upload upload, LocalDate expirationDate, Integer remainingUses) {
        this.code = code;
        this.upload = upload;
        this.expirationDate = expirationDate;
        this.remainingUses = remainingUses;
        this.createdAt = LocalDateTime.now();
    }

    public UploadShareLink() {
    }
}
