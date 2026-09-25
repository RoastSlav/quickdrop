package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.ShortLink;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link ShortLink} hierarchy.
 *
 * <p>Renamed from {@code ShareTokenRepository} as part of merging file/paste share links
 * and general URL-shortener redirect links into one table. Method <em>names</em> are kept
 * identical to the pre-merge repository on purpose, even where the underlying entity field
 * names changed ({@code file} → {@code upload}, {@code shareToken} → {@code code},
 * {@code tokenExpirationDate} → {@code expirationDate}, {@code numberOfAllowedDownloads} →
 * {@code remainingUses}) — every query below is written explicitly with {@code @Query}
 * rather than relying on Spring Data's name-derivation, so callers throughout the codebase
 * did not need to change.
 *
 * <p>Queries scoped to {@code UploadShareLink} (rather than the {@link ShortLink} base type)
 * intentionally only ever match upload-share links; {@link RedirectLink} rows never appear
 * in their results. {@link #existsByShareToken} and
 * {@link #deleteByIdTransactional} are scoped to the base type since code uniqueness and
 * id-based deletion are meant to span every link type sharing the same table.
 */
public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    /** {@code empty} if not found, or if the code belongs to a different link type. */
    @Query("SELECT s FROM UploadShareLink s WHERE s.code = :shareToken")
    Optional<UploadShareLink> findByShareToken(@Param("shareToken") String shareToken);

    /**
     * Finds any {@link ShortLink} by code regardless of target type. Used by callers (QR
     * generation, the general {@code /s/{code}} resolver) that need to look a code up before
     * knowing what kind of link it is.
     */
    @Query("SELECT s FROM ShortLink s WHERE s.code = :code")
    Optional<ShortLink> findByCode(@Param("code") String code);

    /** Checked across every link type sharing the {@code short_link} table. */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM ShortLink s WHERE s.code = :shareToken")
    boolean existsByShareToken(@Param("shareToken") String shareToken);

    /**
     * Case-insensitive existence check, used only for human-chosen aliases — {@code PayPal}
     * and {@code paypal} resolving to different links is a visual-confusability risk that
     * randomly-generated codes don't have, so alias validation checks this instead of
     * {@link #existsByShareToken}.
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM ShortLink s WHERE LOWER(s.code) = LOWER(:code)")
    boolean existsByCodeIgnoreCase(@Param("code") String code);

    @Modifying
    @Transactional
    @Query("DELETE FROM UploadShareLink s WHERE s.upload = :upload")
    void deleteAllByFile(@Param("upload") Upload upload);

    /** Expiry passed OR use allowance exhausted. */
    @Query("SELECT s FROM UploadShareLink s LEFT JOIN FETCH s.upload WHERE s.expirationDate < :today OR s.remainingUses = 0")
    List<UploadShareLink> getShareTokenEntitiesForDeletion(@Param("today") LocalDate today);

    /**
     * Used by the background sidecar-encryption task to mark a link ready without touching
     * any other columns (avoiding a spurious {@code createdAt} update, etc.).
     */
    @Modifying
    @Transactional
    @Query("UPDATE UploadShareLink s SET s.sidecarReady = true WHERE s.id = :id")
    void markSidecarReady(@Param("id") Long id);

    /**
     * Deletes a link in its own transaction. Used by the background sidecar-encryption task
     * on failure, so the caller doesn't need an active Spring-managed transaction.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ShortLink s WHERE s.id = :id")
    void deleteByIdTransactional(@Param("id") Long id);

    /**
     * Single UPDATE, not read-then-write, so two concurrent downloads can't both observe
     * {@code remainingUses = 1} and both succeed. Returns 1 if decremented, 0 if already zero.
     */
    @Modifying
    @Transactional
    @Query("UPDATE UploadShareLink s SET s.remainingUses = s.remainingUses - 1 WHERE s.id = :id AND s.remainingUses > 0")
    int decrementDownloadCount(@Param("id") Long id);

    /**
     * Distinct from the inherited {@link #findById} (which returns the base {@link ShortLink}
     * type) so callers needing {@link UploadShareLink}-specific fields don't have to downcast.
     */
    @Query("SELECT s FROM UploadShareLink s WHERE s.id = :id")
    Optional<UploadShareLink> findUploadLinkById(@Param("id") Long id);

    /** Used to reuse an existing unlimited (no expiry, no use cap) link instead of creating a new one. */
    @Query("SELECT s FROM UploadShareLink s WHERE s.upload = :upload AND s.expirationDate IS NULL AND s.remainingUses IS NULL")
    Optional<UploadShareLink> findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(@Param("upload") Upload upload);

    /** Used when deleting an upload, to clean up all associated sidecars before removing link rows. */
    @Query("SELECT s FROM UploadShareLink s WHERE s.upload = :upload")
    List<UploadShareLink> findAllByFile(@Param("upload") Upload upload);

    /**
     * True if the upload has a link that's neither expired nor exhausted. Used by the
     * maintenance job to decide whether a legacy {@code {uuid}-decrypted} sidecar should be
     * preserved.
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM UploadShareLink s " +
            "WHERE s.upload = :upload " +
            "AND (s.expirationDate IS NULL OR s.expirationDate >= :today) " +
            "AND (s.remainingUses IS NULL OR s.remainingUses > 0)")
    boolean existsValidTokenForFile(@Param("upload") Upload upload, @Param("today") LocalDate today);

    /**
     * Returns the storage keys for all encrypted share sidecars, built as
     * {@code {uuid}-share-{code}}. Uses a native SQL INNER JOIN so that any links
     * referencing a no-longer-existing upload are silently skipped, preventing
     * {@code EntityNotFoundException} during storage migration.
     */
    @Query(value = "SELECT u.uuid || '-share-' || sl.code " +
            "FROM short_link sl INNER JOIN upload u ON sl.upload_id = u.id " +
            "WHERE sl.target_type = 'UPLOAD' AND sl.share_key_hash IS NOT NULL",
            nativeQuery = true)
    List<String> findShareSidecarKeys();

    /**
     * Same "active" predicate as {@link #findFiltered} minus the user filters. Used to label
     * the admin Links tabs, so switching between link kinds shows counts before you click.
     */
    @Query("SELECT COUNT(s) FROM UploadShareLink s WHERE " +
            "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
            "(s.remainingUses IS NULL OR s.remainingUses > 0)")
    long countActiveShareLinks(@Param("today") LocalDate today);

    /** Mirror of {@link #countActiveShareLinks} for the other {@code ShortLink} subtype. */
    @Query("SELECT COUNT(s) FROM RedirectLink s WHERE " +
            "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
            "(s.remainingUses IS NULL OR s.remainingUses > 0)")
    long countActiveRedirectLinks(@Param("today") LocalDate today);

    /**
     * A link is active when its expiry is {@code null}/future AND its use allowance is
     * {@code null}/positive. Each filter's "no constraint" value: {@code isPaste = null}
     * (both), {@code noExpiry = false}, {@code unlimited = false}, {@code query = null}.
     */
    @Query(value = "SELECT s FROM UploadShareLink s JOIN FETCH s.upload WHERE " +
            "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
            "(s.remainingUses IS NULL OR s.remainingUses > 0) AND " +
            "(:isPaste IS NULL OR (:isPaste = true AND TYPE(s.upload) = Paste) OR (:isPaste = false AND TYPE(s.upload) = StoredFile)) AND " +
            "(:noExpiry = false OR s.expirationDate IS NULL) AND " +
            "(:unlimited = false OR s.remainingUses IS NULL) AND " +
            "(:query IS NULL OR LOWER(s.upload.name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(s.code) LIKE LOWER(CONCAT('%', :query, '%')))",
            countQuery = "SELECT COUNT(s) FROM UploadShareLink s WHERE " +
                    "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
                    "(s.remainingUses IS NULL OR s.remainingUses > 0) AND " +
                    "(:isPaste IS NULL OR (:isPaste = true AND TYPE(s.upload) = Paste) OR (:isPaste = false AND TYPE(s.upload) = StoredFile)) AND " +
                    "(:noExpiry = false OR s.expirationDate IS NULL) AND " +
                    "(:unlimited = false OR s.remainingUses IS NULL) AND " +
                    "(:query IS NULL OR LOWER(s.upload.name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(s.code) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<UploadShareLink> findFiltered(
            @Param("today") LocalDate today,
            @Param("isPaste") Boolean isPaste,
            @Param("noExpiry") boolean noExpiry,
            @Param("unlimited") boolean unlimited,
            @Param("query") String query,
            Pageable pageable);

    /** Redirect-link counterpart of {@link #findFiltered}; see its javadoc for filter semantics. */
    @Query(value = "SELECT s FROM RedirectLink s WHERE " +
            "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
            "(s.remainingUses IS NULL OR s.remainingUses > 0) AND " +
            "(:noExpiry = false OR s.expirationDate IS NULL) AND " +
            "(:unlimited = false OR s.remainingUses IS NULL) AND " +
            "(:query IS NULL OR LOWER(s.targetUrl) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(s.code) LIKE LOWER(CONCAT('%', :query, '%')))",
            countQuery = "SELECT COUNT(s) FROM RedirectLink s WHERE " +
                    "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
                    "(s.remainingUses IS NULL OR s.remainingUses > 0) AND " +
                    "(:noExpiry = false OR s.expirationDate IS NULL) AND " +
                    "(:unlimited = false OR s.remainingUses IS NULL) AND " +
                    "(:query IS NULL OR LOWER(s.targetUrl) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(s.code) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<RedirectLink> findFilteredRedirectLinks(
            @Param("today") LocalDate today,
            @Param("noExpiry") boolean noExpiry,
            @Param("unlimited") boolean unlimited,
            @Param("query") String query,
            Pageable pageable);

    /**
     * Distinct from the inherited {@link #findById} (which returns the base {@link ShortLink}
     * type) so callers needing {@link RedirectLink}-specific fields don't have to downcast.
     */
    @Query("SELECT s FROM RedirectLink s WHERE s.id = :id")
    Optional<RedirectLink> findRedirectLinkById(@Param("id") Long id);

    /** Mirrors {@link #getShareTokenEntitiesForDeletion} for the {@link RedirectLink} subtype. */
    @Query("SELECT s FROM RedirectLink s WHERE s.expirationDate < :today OR s.remainingUses = 0")
    List<RedirectLink> getRedirectLinksForDeletion(@Param("today") LocalDate today);
}
