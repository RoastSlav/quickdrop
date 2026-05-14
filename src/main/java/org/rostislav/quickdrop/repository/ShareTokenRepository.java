package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
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
 * Repository for {@link ShareTokenEntity}.
 *
 * <p>Provides token lookup, existence checks, and a bulk-delete for file cleanup.
 * Expired or exhausted tokens are collected by
 * {@link #getShareTokenEntitiesForDeletion(LocalDate)} and removed by the nightly sweep
 * in {@link org.rostislav.quickdrop.service.ScheduleService#cleanShareTokens()}.
 *
 * <p><strong>SQLite date storage note:</strong> Hibernate stores {@link LocalDate} and
 * {@link java.time.LocalDateTime} as epoch-millisecond integers in SQLite. JPQL's
 * {@code CURRENT_DATE} function generates {@code date('now')} which returns a text
 * string; comparing an integer column against a text value always evaluates to
 * {@code false} in SQLite (integers sort before text). All date-range predicates in
 * this repository therefore bind a Java {@code LocalDate} parameter instead of using
 * {@code CURRENT_DATE} so that Hibernate serialises both sides to the same integer
 * representation.
 */
public interface ShareTokenRepository extends JpaRepository<ShareTokenEntity, Long> {

    /**
     * Finds a token entity by its short token string.
     *
     * @param shareToken the token value to look up
     * @return the matching entity, or empty if not found
     */
    Optional<ShareTokenEntity> findByShareToken(String shareToken);

    /**
     * Checks whether a given token string already exists (used during token generation
     * to guarantee uniqueness).
     *
     * @param shareToken the candidate token string
     * @return {@code true} if the token is already in use
     */
    boolean existsByShareToken(String shareToken);

    /**
     * Removes all share tokens associated with a given file (called before the file
     * is deleted to avoid orphaned token rows).
     *
     * @param fileEntity the file whose tokens should be removed
     */
    void deleteAllByFile(FileEntity fileEntity);

    /**
     * Returns all tokens that are no longer valid — either their expiry date has
     * passed or their download allowance has been exhausted.
     *
     * @param today today's date, used as the expiry cutoff (pass {@code LocalDate.now()})
     * @return list of tokens eligible for deletion
     */
    @Query("SELECT s FROM ShareTokenEntity s WHERE s.tokenExpirationDate < :today OR s.numberOfAllowedDownloads = 0")
    List<ShareTokenEntity> getShareTokenEntitiesForDeletion(@Param("today") LocalDate today);

    /**
     * Flips {@code sidecarReady} to {@code true} for a single token. Used by the
     * background sidecar-encryption task to mark the token as ready without touching
     * any other columns (avoiding spurious updates to {@code createdAt} etc.).
     *
     * @param id the token's database id
     */
    @Modifying
    @Transactional
    @Query("UPDATE ShareTokenEntity s SET s.sidecarReady = true WHERE s.id = :id")
    void markSidecarReady(@Param("id") Long id);

    /**
     * Deletes a token by id inside its own transaction. Used by the background
     * sidecar-encryption task when encryption fails, so the caller doesn't need an
     * active Spring-managed transaction.
     *
     * @param id the token's database id
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ShareTokenEntity s WHERE s.id = :id")
    void deleteByIdTransactional(@Param("id") Long id);

    /**
     * Finds the first unlimited token (no expiry, no download cap) for a given file.
     * Used to reuse an existing unlimited token instead of creating a new one.
     *
     * @param file the file to look up the token for
     * @return the existing unlimited token, or empty if none exists
     */
    Optional<ShareTokenEntity> findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(FileEntity file);

    /**
     * Returns all share tokens for the given file. Used when deleting a file to clean
     * up all associated sidecars before removing token rows.
     *
     * @param file the file whose tokens should be returned
     * @return all tokens associated with the file
     */
    List<ShareTokenEntity> findAllByFile(FileEntity file);

    /**
     * Returns {@code true} if the file has at least one share token that has neither
     * expired nor exhausted its download allowance. Used by the maintenance job to
     * decide whether a legacy {@code {uuid}-decrypted} sidecar should be preserved.
     *
     * @param file  the file to check
     * @param today today's date, used as the expiry cutoff (pass {@code LocalDate.now()})
     * @return {@code true} if an active share token exists
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM ShareTokenEntity s WHERE s.file = :file AND (s.tokenExpirationDate IS NULL OR s.tokenExpirationDate >= :today) AND (s.numberOfAllowedDownloads IS NULL OR s.numberOfAllowedDownloads > 0)")
    boolean existsValidTokenForFile(@Param("file") FileEntity file, @Param("today") LocalDate today);

    /**
     * Returns a filtered, sorted, paginated page of currently-active share tokens.
     * A token is active when its expiry date is {@code null} or in the future AND its
     * download allowance is {@code null} or greater than zero.
     *
     * <p>Any filter parameter that represents "no constraint" should be passed as
     * {@code null} / {@code false}:
     * <ul>
     *   <li>{@code isPaste = null} — include both files and pastes</li>
     *   <li>{@code noExpiry = false} — include tokens with and without an expiry date</li>
     *   <li>{@code unlimited = false} — include tokens with and without a download cap</li>
     *   <li>{@code query = null} — no name/token substring filter</li>
     * </ul>
     *
     * <p>Sort order is controlled by the {@link Pageable} argument. Supported sort
     * properties: {@code createdAt}, {@code id}, {@code tokenExpirationDate},
     * {@code numberOfAllowedDownloads}, {@code file.name}.
     *
     * @param today     today's date used as the expiry cutoff (pass {@code LocalDate.now()})
     * @param isPaste   {@code true} = pastes only, {@code false} = files only,
     *                  {@code null} = both
     * @param noExpiry  when {@code true} restrict to tokens with no expiry date
     * @param unlimited when {@code true} restrict to tokens with no download cap
     * @param query     optional case-insensitive substring matched against file name
     *                  and token string; pass {@code null} to skip
     * @param pageable  pagination and sort configuration
     * @return page of matching active tokens
     */
    @Query(value = "SELECT s FROM ShareTokenEntity s WHERE " +
            "(s.tokenExpirationDate IS NULL OR s.tokenExpirationDate >= :today) AND " +
            "(s.numberOfAllowedDownloads IS NULL OR s.numberOfAllowedDownloads > 0) AND " +
            "(:isPaste IS NULL OR s.file.paste = :isPaste) AND " +
            "(:noExpiry = false OR s.tokenExpirationDate IS NULL) AND " +
            "(:unlimited = false OR s.numberOfAllowedDownloads IS NULL) AND " +
            "(:query IS NULL OR LOWER(s.file.name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(s.shareToken) LIKE LOWER(CONCAT('%', :query, '%')))",
            countQuery = "SELECT COUNT(s) FROM ShareTokenEntity s WHERE " +
                    "(s.tokenExpirationDate IS NULL OR s.tokenExpirationDate >= :today) AND " +
                    "(s.numberOfAllowedDownloads IS NULL OR s.numberOfAllowedDownloads > 0) AND " +
                    "(:isPaste IS NULL OR s.file.paste = :isPaste) AND " +
                    "(:noExpiry = false OR s.tokenExpirationDate IS NULL) AND " +
                    "(:unlimited = false OR s.numberOfAllowedDownloads IS NULL) AND " +
                    "(:query IS NULL OR LOWER(s.file.name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(s.shareToken) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<ShareTokenEntity> findFiltered(
            @Param("today") LocalDate today,
            @Param("isPaste") Boolean isPaste,
            @Param("noExpiry") boolean noExpiry,
            @Param("unlimited") boolean unlimited,
            @Param("query") String query,
            Pageable pageable);
}
