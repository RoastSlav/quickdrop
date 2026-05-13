package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ShareTokenEntity}.
 *
 * <p>Provides token lookup, existence checks, and a bulk-delete for file cleanup.
 * Expired or exhausted tokens are collected by
 * {@link #getShareTokenEntitiesForDeletion()} and removed by the nightly sweep
 * in {@link org.rostislav.quickdrop.service.ScheduleService#cleanShareTokens()}.
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
     * @return list of tokens eligible for deletion
     */
    @Query("SELECT s FROM ShareTokenEntity s WHERE s.tokenExpirationDate < CURRENT_DATE OR s.numberOfAllowedDownloads = 0")
    List<ShareTokenEntity> getShareTokenEntitiesForDeletion();

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
     * @param file the file to check
     * @return {@code true} if an active share token exists
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM ShareTokenEntity s WHERE s.file = :file AND (s.tokenExpirationDate IS NULL OR s.tokenExpirationDate >= CURRENT_DATE) AND (s.numberOfAllowedDownloads IS NULL OR s.numberOfAllowedDownloads > 0)")
    boolean existsValidTokenForFile(@Param("file") FileEntity file);
}
