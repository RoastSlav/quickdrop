package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.model.FileEntityView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link StoredFile} with custom queries for listing, searching,
 * aggregation, and scheduled cleanup.
 *
 * <p>Several queries return the {@link FileEntityView} projection that includes
 * aggregated download counts via a LEFT JOIN against
 * {@link org.rostislav.quickdrop.entity.ActivityLog}, avoiding a separate count
 * query per row.
 *
 * <p>All "live" queries filter {@code f.deleted = false} so that soft-deleted records
 * are excluded.  Lookup by UUID ({@link UploadRepository#findByUUID}) returns
 * soft-deleted records so admin controllers can still render the deleted-file view.
 *
 * <p>Paste-specific queries live in {@link PasteRepository}.  Queries that span both
 * subtypes (orphan scan, deletion eligibility) live in {@link UploadRepository}.
 */
public interface FileRepository extends JpaRepository<StoredFile, Long> {

    /**
     * Looks up a stored file by its UUID path segment.
     * Returns soft-deleted records so admin controllers can still find them.
     *
     * @param uuid the file's unique identifier
     * @return the matching entity, or empty if not found (or if the UUID belongs to a paste)
     */
    @Query("SELECT f FROM StoredFile f WHERE f.uuid = :uuid")
    Optional<StoredFile> findByUUID(@Param("uuid") String uuid);

    /**
     * Returns a paginated list of visible (non-hidden, non-deleted) files, newest first.
     *
     * @param pageable pagination and sort parameters
     * @return page of visible files
     */
    @Query("SELECT f FROM StoredFile f WHERE f.hidden = false AND f.deleted = false ORDER BY f.uploadDate DESC")
    Page<StoredFile> findAllNotHiddenFiles(Pageable pageable);

    /**
     * Returns the total storage consumed by live (non-deleted) file entries in bytes.
     *
     * @return sum of file sizes, or {@code null} if there are no files
     */
    @Query("SELECT SUM(f.size) FROM StoredFile f WHERE f.deleted = false")
    Long totalFileSizeForFilesOnly();

    /**
     * Returns the total number of live (non-deleted) file entries.
     */
    @Query("SELECT COUNT(f) FROM StoredFile f WHERE f.deleted = false")
    long countFiles();

    /**
     * Full-text search over visible, live files (name, description, UUID), newest first.
     *
     * @param query    search string (case-insensitive, partial-match)
     * @param pageable pagination parameters
     * @return matching page of visible files
     */
    @Query(value = "SELECT f FROM StoredFile f WHERE f.hidden = false AND f.deleted = false " +
            "AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
            "OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
            "OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%'))) ORDER BY f.uploadDate DESC",
            countQuery = "SELECT COUNT(f) FROM StoredFile f WHERE f.hidden = false AND f.deleted = false " +
                    "AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<StoredFile> searchNotHiddenFiles(@Param("searchString") String query, Pageable pageable);

    /**
     * Returns a paginated list of live files (admin view) with their total download
     * counts computed in a single JOIN query.
     *
     * @param pageable pagination parameters
     * @return page of {@link FileEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.FileEntityView(
                    f,
                    CAST(SUM(CASE WHEN dl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM StoredFile f
                LEFT JOIN ActivityLog dl ON dl.file.id = f.id AND dl.eventType = 'DOWNLOAD'
                WHERE f.deleted = false
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM StoredFile f WHERE f.deleted = false")
    Page<FileEntityView> findFilesWithDownloadCounts(Pageable pageable);

    /**
     * Search variant of {@link #findFilesWithDownloadCounts} filtered by a query string.
     *
     * @param query    search string (case-insensitive, partial-match on name, description, UUID)
     * @param pageable pagination parameters
     * @return matching page of {@link FileEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.FileEntityView(
                    f,
                    CAST(SUM(CASE WHEN dl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM StoredFile f
                LEFT JOIN ActivityLog dl ON dl.file.id = f.id AND dl.eventType = 'DOWNLOAD'
                WHERE f.deleted = false
                    AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM StoredFile f WHERE f.deleted = false " +
                    "AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<FileEntityView> searchFilesWithDownloadCounts(@Param("searchString") String query, Pageable pageable);

    /**
     * Returns a paginated list of soft-deleted files (admin deleted tab) with their
     * total download counts.
     *
     * @param pageable pagination parameters
     * @return page of {@link FileEntityView} projections for deleted files
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.FileEntityView(
                    f,
                    CAST(SUM(CASE WHEN dl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM StoredFile f
                LEFT JOIN ActivityLog dl ON dl.file.id = f.id AND dl.eventType = 'DOWNLOAD'
                WHERE f.deleted = true
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM StoredFile f WHERE f.deleted = true")
    Page<FileEntityView> findDeletedFilesWithDownloadCounts(Pageable pageable);

    /**
     * Search variant of {@link #findDeletedFilesWithDownloadCounts}.
     *
     * @param query    search string (case-insensitive, partial-match on name, description, UUID)
     * @param pageable pagination parameters
     * @return matching page of deleted {@link FileEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.FileEntityView(
                    f,
                    CAST(SUM(CASE WHEN dl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM StoredFile f
                LEFT JOIN ActivityLog dl ON dl.file.id = f.id AND dl.eventType = 'DOWNLOAD'
                WHERE f.deleted = true
                    AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM StoredFile f WHERE f.deleted = true " +
                    "AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<FileEntityView> searchDeletedFilesWithDownloadCounts(@Param("searchString") String query, Pageable pageable);
}
