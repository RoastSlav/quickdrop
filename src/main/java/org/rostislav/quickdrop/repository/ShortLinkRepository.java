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
 * intentionally only ever match upload-share links; a future {@code RedirectLink} subtype
 * will not appear in their results. {@link #existsByShareToken} and
 * {@link #deleteByIdTransactional} are scoped to the base type since code uniqueness and
 * id-based deletion are meant to span every link type sharing the same table.
 */
public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    /**
     * Finds an upload-share link by its short code string.
     *
     * @param shareToken the code value to look up
     * @return the matching link, or empty if not found (or not an upload-share link)
     */
    @Query("SELECT s FROM UploadShareLink s WHERE s.code = :shareToken")
    Optional<UploadShareLink> findByShareToken(@Param("shareToken") String shareToken);

    /**
     * Finds any {@link ShortLink} by its code, regardless of target type. Used by callers
     * (QR generation today; the general resolver in a later change) that need to look a
     * code up before knowing what kind of link it is.
     *
     * @param code the code value to look up
     * @return the matching link, or empty if not found
     */
    @Query("SELECT s FROM ShortLink s WHERE s.code = :code")
    Optional<ShortLink> findByCode(@Param("code") String code);

    /**
     * Checks whether a given code string already exists, across every link type sharing
     * the {@code short_link} table.
     *
     * @param shareToken the candidate code string
     * @return {@code true} if the code is already in use
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM ShortLink s WHERE s.code = :shareToken")
    boolean existsByShareToken(@Param("shareToken") String shareToken);

    /**
     * Case-insensitive existence check, used only for human-chosen aliases — {@code PayPal}
     * and {@code paypal} resolving to different links is a visual-confusability risk that
     * randomly-generated codes don't have, so alias validation checks this instead of
     * {@link #existsByShareToken}.
     *
     * @param code the candidate alias
     * @return {@code true} if any link (of any target type) already uses this code, ignoring case
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM ShortLink s WHERE LOWER(s.code) = LOWER(:code)")
    boolean existsByCodeIgnoreCase(@Param("code") String code);

    /**
     * Removes all upload-share links associated with a given upload.
     *
     * @param upload the upload whose links should be removed
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM UploadShareLink s WHERE s.upload = :upload")
    void deleteAllByFile(@Param("upload") Upload upload);

    /**
     * Returns all upload-share links that are no longer valid — either their expiry date
     * has passed or their use allowance has been exhausted.
     *
     * @param today today's date, used as the expiry cutoff (pass {@code LocalDate.now()})
     * @return list of links eligible for deletion
     */
    @Query("SELECT s FROM UploadShareLink s LEFT JOIN FETCH s.upload WHERE s.expirationDate < :today OR s.remainingUses = 0")
    List<UploadShareLink> getShareTokenEntitiesForDeletion(@Param("today") LocalDate today);

    /**
     * Flips {@code sidecarReady} to {@code true} for a single link. Used by the
     * background sidecar-encryption task to mark the link as ready without touching
     * any other columns (avoiding spurious updates to {@code createdAt} etc.).
     *
     * @param id the link's database id
     */
    @Modifying
    @Transactional
    @Query("UPDATE UploadShareLink s SET s.sidecarReady = true WHERE s.id = :id")
    void markSidecarReady(@Param("id") Long id);

    /**
     * Deletes a link by id inside its own transaction. Used by the background
     * sidecar-encryption task when encryption fails, so the caller doesn't need an
     * active Spring-managed transaction.
     *
     * @param id the link's database id
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ShortLink s WHERE s.id = :id")
    void deleteByIdTransactional(@Param("id") Long id);

    /**
     * Atomically decrements the remaining-uses counter for an upload-share link, but only
     * when the counter is currently greater than zero. Returns the number of rows updated
     * (1 on success, 0 if the counter was already exhausted by a concurrent request).
     *
     * <p>Using a single UPDATE prevents a read-then-write race where two concurrent
     * downloads both observe {@code remainingUses = 1} and both succeed.
     *
     * @param id the link's database id
     * @return 1 if the counter was decremented, 0 if it was already zero
     */
    @Modifying
    @Transactional
    @Query("UPDATE UploadShareLink s SET s.remainingUses = s.remainingUses - 1 WHERE s.id = :id AND s.remainingUses > 0")
    int decrementDownloadCount(@Param("id") Long id);

    /**
     * Finds an upload-share link by id. Distinct from the inherited {@link #findById}
     * (which returns the base {@link ShortLink} type) so callers that need
     * {@link UploadShareLink}-specific fields don't have to downcast.
     *
     * @param id the link's database id
     * @return the matching upload-share link, or empty if not found (or a different subtype)
     */
    @Query("SELECT s FROM UploadShareLink s WHERE s.id = :id")
    Optional<UploadShareLink> findUploadLinkById(@Param("id") Long id);

    /**
     * Finds the first unlimited link (no expiry, no use cap) for a given upload.
     * Used to reuse an existing unlimited link instead of creating a new one.
     *
     * @param upload the upload to look up the link for
     * @return the existing unlimited link, or empty if none exists
     */
    @Query("SELECT s FROM UploadShareLink s WHERE s.upload = :upload AND s.expirationDate IS NULL AND s.remainingUses IS NULL")
    Optional<UploadShareLink> findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(@Param("upload") Upload upload);

    /**
     * Returns all upload-share links for the given upload. Used when deleting an upload to
     * clean up all associated sidecars before removing link rows.
     *
     * @param upload the upload whose links should be returned
     * @return all links associated with the upload
     */
    @Query("SELECT s FROM UploadShareLink s WHERE s.upload = :upload")
    List<UploadShareLink> findAllByFile(@Param("upload") Upload upload);

    /**
     * Returns {@code true} if the upload has at least one upload-share link that has
     * neither expired nor exhausted its use allowance. Used by the maintenance job to
     * decide whether a legacy {@code {uuid}-decrypted} sidecar should be preserved.
     *
     * @param upload the upload to check
     * @param today  today's date, used as the expiry cutoff (pass {@code LocalDate.now()})
     * @return {@code true} if an active link exists
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
     *
     * @return list of sidecar storage keys for upload-share links that have a {@code shareKeyHash}
     */
    @Query(value = "SELECT u.uuid || '-share-' || sl.code " +
            "FROM short_link sl INNER JOIN upload u ON sl.upload_id = u.id " +
            "WHERE sl.target_type = 'UPLOAD' AND sl.share_key_hash IS NOT NULL",
            nativeQuery = true)
    List<String> findShareSidecarKeys();

    /**
     * Counts still-active upload-share links, using the same "active" predicate as
     * {@link #findFiltered} (not expired, uses remaining) minus the user filters.
     *
     * <p>Used to label the admin Links tabs, so switching between link kinds shows
     * how many of each exist before you click.
     *
     * @param today current date
     * @return number of active share links
     */
    @Query("SELECT COUNT(s) FROM UploadShareLink s WHERE " +
            "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
            "(s.remainingUses IS NULL OR s.remainingUses > 0)")
    long countActiveShareLinks(@Param("today") LocalDate today);

    /**
     * Counts still-active redirect links. Mirror of {@link #countActiveShareLinks}
     * for the other {@code ShortLink} subtype.
     *
     * @param today current date
     * @return number of active redirect links
     */
    @Query("SELECT COUNT(s) FROM RedirectLink s WHERE " +
            "(s.expirationDate IS NULL OR s.expirationDate >= :today) AND " +
            "(s.remainingUses IS NULL OR s.remainingUses > 0)")
    long countActiveRedirectLinks(@Param("today") LocalDate today);

    /**
     * Returns a filtered, sorted, paginated page of currently-active upload-share links.
     * A link is active when its expiry date is {@code null} or in the future AND its
     * use allowance is {@code null} or greater than zero.
     *
     * <p>Any filter parameter that represents "no constraint" should be passed as
     * {@code null} / {@code false}:
     * <ul>
     *   <li>{@code isPaste = null} — include links for both files and pastes</li>
     *   <li>{@code noExpiry = false} — include links with and without an expiry date</li>
     *   <li>{@code unlimited = false} — include links with and without a use cap</li>
     *   <li>{@code query = null} — no name/code substring filter</li>
     * </ul>
     *
     * @param today     today's date used as the expiry cutoff (pass {@code LocalDate.now()})
     * @param isPaste   {@code true} = pastes only, {@code false} = files only,
     *                  {@code null} = both
     * @param noExpiry  when {@code true} restrict to links with no expiry date
     * @param unlimited when {@code true} restrict to links with no use cap
     * @param query     optional case-insensitive substring matched against upload name
     *                  and code string; pass {@code null} to skip
     * @param pageable  pagination and sort configuration
     * @return page of matching active links
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

    /**
     * Returns a filtered, sorted, paginated page of currently-active redirect
     * (URL-shortener) links. A link is active when its expiry date is {@code null} or in
     * the future AND its use allowance is {@code null} or greater than zero.
     *
     * @param today     today's date used as the expiry cutoff (pass {@code LocalDate.now()})
     * @param noExpiry  when {@code true} restrict to links with no expiry date
     * @param unlimited when {@code true} restrict to links with no use cap
     * @param query     optional case-insensitive substring matched against the destination
     *                  URL and code string; pass {@code null} to skip
     * @param pageable  pagination and sort configuration
     * @return page of matching active redirect links
     */
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
     * Finds a redirect link by id. Distinct from the inherited {@link #findById}
     * (which returns the base {@link ShortLink} type) so callers that need
     * {@link RedirectLink}-specific fields don't have to downcast.
     *
     * @param id the link's database id
     * @return the matching redirect link, or empty if not found (or a different subtype)
     */
    @Query("SELECT s FROM RedirectLink s WHERE s.id = :id")
    Optional<RedirectLink> findRedirectLinkById(@Param("id") Long id);

    /**
     * Returns all redirect links that are no longer valid — either their expiry date has
     * passed or their use allowance has been exhausted. Mirrors
     * {@link #getShareTokenEntitiesForDeletion} for the {@link RedirectLink} subtype.
     *
     * @param today today's date, used as the expiry cutoff (pass {@code LocalDate.now()})
     * @return list of redirect links eligible for deletion
     */
    @Query("SELECT s FROM RedirectLink s WHERE s.expirationDate < :today OR s.remainingUses = 0")
    List<RedirectLink> getRedirectLinksForDeletion(@Param("today") LocalDate today);
}
