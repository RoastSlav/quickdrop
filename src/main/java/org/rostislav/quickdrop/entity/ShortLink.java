package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A short code that resolves to something else when visited at {@code /{prefix}/{code}}.
 *
 * <p>Single-table inheritance keyed on {@code target_type}: {@link UploadShareLink} is the
 * only subtype today (it is the renamed {@code ShareTokenEntity}, still served at the legacy
 * {@code /share/{token}} route). A {@code RedirectLink} subtype for plain URL shortening is
 * added in a later change.
 *
 * <p>Two separate counters exist deliberately: {@link #remainingUses} preserves the exact
 * atomic-decrement semantics the old {@code number_of_allowed_downloads} column had (see
 * {@link org.rostislav.quickdrop.repository.ShortLinkRepository#decrementDownloadCount}) —
 * collapsing it with {@link #useCount} would reintroduce the concurrent-download race that
 * decrement query exists to prevent. {@link #useCount} is a monotonically increasing
 * analytics counter, wired up separately.
 */
@Entity
@Table(name = "short_link")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "target_type")
public abstract class ShortLink {
    /**
     * The URL-safe code string (5+ characters, unique across every link in the system,
     * regardless of subtype).
     */
    @Column(name = "code", nullable = false, unique = true, length = 64)
    public String code;
    /**
     * Optional expiry date; {@code null} means the link never expires by date.
     */
    @Column(name = "expiration_date")
    public LocalDate expirationDate;
    /**
     * Remaining use allowance; decremented on each use. {@code null} means unlimited.
     */
    @Column(name = "remaining_uses")
    public Integer remainingUses;
    /**
     * Total number of times this link has been used. Analytics-only; never gates access.
     */
    @Column(name = "use_count", nullable = false)
    public int useCount = 0;
    /**
     * Timestamp when this link was created.
     */
    @Column(name = "created_at")
    public LocalDateTime createdAt;
    /**
     * Optional password required before the link resolves. {@code null} means no password.
     */
    @Column(name = "password_hash")
    public String passwordHash;
    /**
     * Per-link override for whether an interstitial preview page is shown before resolving.
     * {@code null} means follow the global setting.
     */
    @Column(name = "interstitial")
    public String interstitial;
    @Column(name = "created_by_admin", nullable = false)
    public boolean createdByAdmin = false;
    @Column(name = "creator_ip")
    public String creatorIp;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() {
        return id;
    }
}
