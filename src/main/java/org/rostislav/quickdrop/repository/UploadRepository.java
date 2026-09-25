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

    /** Returns soft-deleted records too, so admin controllers can still find them. */
    @Query("SELECT u FROM Upload u WHERE u.uuid = :uuid")
    Optional<Upload> findByUUID(@Param("uuid") String uuid);

    /**
     * Whether a live upload already carries this display name. Used to keep the generated
     * name of a multi-file bundle distinct; soft-deleted rows do not reserve their name.
     */
    @Query("SELECT COUNT(u) > 0 FROM Upload u WHERE u.name = :name AND u.deleted = false")
    boolean existsByNameAndNotDeleted(@Param("name") String name);

    /**
     * For the orphan-scan job. Soft-deleted uploads are intentionally excluded because they
     * legitimately have no file on disk.
     */
    @Query("SELECT u FROM Upload u WHERE u.deleted = false")
    Page<Upload> findAllNotDeleted(Pageable pageable);

    /** {@code null} if the table is empty. */
    @Query("SELECT SUM(u.size) FROM Upload u WHERE u.deleted = false")
    Long totalSizeOfAllUploads();

    /** Non-pinned ({@code keepIndefinitely = false}) uploads eligible for scheduled deletion. */
    @Query("SELECT u FROM Upload u WHERE u.keepIndefinitely = false AND u.deleted = false AND u.uploadDate < :thresholdDate")
    List<Upload> getUploadsForDeletion(@Param("thresholdDate") LocalDate thresholdDate);

    /**
     * Same predicate as {@link #getUploadsForDeletion(LocalDate)} but returns only the count,
     * so the admin dashboard can surface "expiring soon" without loading every matching entity.
     */
    @Query("SELECT COUNT(u) FROM Upload u WHERE u.keepIndefinitely = false AND u.deleted = false AND u.uploadDate < :thresholdDate")
    long countUploadsExpiringBefore(@Param("thresholdDate") LocalDate thresholdDate);

    /**
     * Use instead of {@link #findAll()} when only the UUID is needed (e.g. storage migration
     * key building), to avoid loading full entity graphs into memory.
     */
    @Query("SELECT u.uuid FROM Upload u WHERE u.deleted = false")
    List<String> findAllActiveUuids();
}
