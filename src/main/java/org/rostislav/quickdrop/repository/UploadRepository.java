package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.Upload;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Upload} base entity, providing operations shared by
 * both {@link org.rostislav.quickdrop.entity.StoredFile} and
 * {@link org.rostislav.quickdrop.entity.Paste} subtypes.
 *
 * <p>Subtype-specific queries live in {@link FileRepository} and
 * {@link PasteRepository} respectively.
 */
public interface UploadRepository extends JpaRepository<Upload, Long> {

    /**
     * Looks up any upload (file or paste) by its UUID path segment.
     * Returns soft-deleted records so admin controllers can still find them.
     *
     * @param uuid the upload's unique identifier
     * @return the matching entity, or empty if not found
     */
    @Query("SELECT u FROM Upload u WHERE u.uuid = :uuid")
    Optional<Upload> findByUUID(@Param("uuid") String uuid);

    /**
     * Whether a live upload already carries this display name. Used to keep the generated
     * name of a multi-file bundle distinct; soft-deleted rows do not reserve their name.
     *
     * @param name the display name to check
     */
    @Query("SELECT COUNT(u) > 0 FROM Upload u WHERE u.name = :name AND u.deleted = false")
    boolean existsByNameAndNotDeleted(@Param("name") String name);

    /**
     * Returns a paginated list of all non-deleted uploads (files and pastes) for
     * the orphan-scan job.  Soft-deleted uploads are intentionally excluded because
     * they legitimately have no file on disk.
     *
     * @param pageable pagination parameters
     * @return page of non-deleted uploads
     */
    @Query("SELECT u FROM Upload u WHERE u.deleted = false")
    Page<Upload> findAllNotDeleted(Pageable pageable);

    /**
     * Returns the total storage consumed by all live (non-deleted) uploads in bytes.
     *
     * @return sum of all upload sizes, or {@code null} if the table is empty
     */
    @Query("SELECT SUM(u.size) FROM Upload u WHERE u.deleted = false")
    Long totalSizeOfAllUploads();

    /**
     * Returns all non-pinned, non-deleted uploads whose upload date is strictly
     * before {@code thresholdDate}, eligible for scheduled deletion.
     *
     * @param thresholdDate uploads older than this date are returned
     * @return list of uploads that should be deleted
     */
    @Query("SELECT u FROM Upload u WHERE u.keepIndefinitely = false AND u.deleted = false AND u.uploadDate < :thresholdDate")
    List<Upload> getUploadsForDeletion(@Param("thresholdDate") LocalDate thresholdDate);

    /**
     * Counts uploads that will be auto-deleted on or before {@code thresholdDate}.
     *
     * <p>Same predicate as {@link #getUploadsForDeletion(LocalDate)} but returns only the
     * count, so the admin dashboard can surface "expiring soon" without loading every
     * matching entity.
     *
     * @param thresholdDate uploads older than this date are counted
     * @return number of uploads due for deletion before the given date
     */
    @Query("SELECT COUNT(u) FROM Upload u WHERE u.keepIndefinitely = false AND u.deleted = false AND u.uploadDate < :thresholdDate")
    long countUploadsExpiringBefore(@Param("thresholdDate") LocalDate thresholdDate);

    /**
     * Returns only the UUID strings of all non-deleted uploads.
     *
     * <p>Use this instead of {@link #findAll()} when only the UUID is needed (e.g. storage
     * migration key building) to avoid loading full entity graphs into memory.
     *
     * @return list of UUIDs for non-deleted uploads
     */
    @Query("SELECT u.uuid FROM Upload u WHERE u.deleted = false")
    List<String> findAllActiveUuids();
}
