package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.PasteEntityView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link FileEntity} with custom queries for listing, searching,
 * aggregation, and scheduled cleanup.
 *
 * <p>Several queries return projected view-models ({@link FileEntityView},
 * {@link PasteEntityView}) that include aggregated download/view counts via
 * a LEFT JOIN against {@link org.rostislav.quickdrop.entity.FileHistoryLog},
 * avoiding a separate count query per row.
 */
public interface FileRepository extends JpaRepository<FileEntity, Long> {

    /**
     * Looks up a file by its UUID path segment.
     *
     * @param uuid the file's unique identifier
     * @return the matching entity, or empty if not found
     */
    @Query("SELECT f FROM FileEntity f WHERE f.uuid = :uuid")
    Optional<FileEntity> findByUUID(@Param("uuid") String uuid);

    /**
     * Returns all non-pinned files whose upload date is strictly before
     * {@code thresholdDate}, eligible for scheduled deletion.
     *
     * @param thresholdDate files older than this date are returned
     * @return list of files that should be deleted
     */
    @Query("SELECT f FROM FileEntity f WHERE f.keepIndefinitely = false AND f.uploadDate < :thresholdDate")
    List<FileEntity> getFilesForDeletion(@Param("thresholdDate") LocalDate thresholdDate);

    /**
     * Returns a paginated list of visible (non-hidden, non-paste) files, newest first.
     *
     * @param pageable pagination and sort parameters
     * @return page of visible files
     */
    @Query("SELECT f FROM FileEntity f WHERE f.hidden = false AND f.paste = false ORDER BY f.uploadDate DESC")
    Page<FileEntity> findAllNotHiddenFiles(Pageable pageable);

    /**
     * Returns the total storage consumed by all entries (files + pastes) in bytes.
     *
     * @return sum of all file sizes, or {@code null} if the table is empty
     */
    @Query("SELECT SUM(f.size) FROM FileEntity f")
    Long totalFileSizeForAllFiles();

    /**
     * Returns the total storage consumed by file entries only (excludes pastes) in bytes.
     *
     * @return sum of file sizes, or {@code null} if there are no files
     */
    @Query("SELECT SUM(f.size) FROM FileEntity f WHERE f.paste = false")
    Long totalFileSizeForFilesOnly();

    /**
     * Returns the total number of non-paste file entries.
     */
    long countByPasteFalse();

    /** Returns the total number of paste entries. */
    long countByPasteTrue();

    /**
     * Returns the average byte length of paste entries.
     *
     * @return average paste size in bytes, or {@code null} if there are no pastes
     */
    @Query("SELECT AVG(f.size) FROM FileEntity f WHERE f.paste = true")
    Double averagePasteLength();

    /** Returns the number of paste entries whose name ends with {@code .md} (Markdown). */
    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.paste = true AND f.name LIKE '%.md'")
    long countMarkdownPastes();

    /**
     * Full-text search over visible files (name, description, UUID), newest first.
     *
     * @param query      search string (case-insensitive, partial-match)
     * @param pageable   pagination parameters
     * @return matching page of visible files
     */
    @Query(value = "SELECT f FROM FileEntity f WHERE f.hidden = false AND f.paste = false AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%'))) ORDER BY f.uploadDate DESC",
            countQuery = "SELECT COUNT(f) FROM FileEntity f WHERE f.hidden = false AND f.paste = false AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<FileEntity> searchNotHiddenFiles(@Param("searchString") String query, Pageable pageable);

    /**
     * Returns a paginated list of all files (admin view) with their total download
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
                FROM FileEntity f
                LEFT JOIN FileHistoryLog dl ON dl.file.id = f.id AND dl.eventType = 'DOWNLOAD'
                WHERE f.paste = false
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM FileEntity f WHERE f.paste = false")
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
                FROM FileEntity f
                LEFT JOIN FileHistoryLog dl ON dl.file.id = f.id AND dl.eventType = 'DOWNLOAD'
                WHERE f.paste = false
                    AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM FileEntity f WHERE f.paste = false AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<FileEntityView> searchFilesWithDownloadCounts(@Param("searchString") String query, Pageable pageable);

    /**
     * Returns a paginated list of all paste entries with their total view counts.
     *
     * @param pageable pagination parameters
     * @return page of {@link PasteEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.PasteEntityView(
                    f,
                    CAST(SUM(CASE WHEN vl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM FileEntity f
                LEFT JOIN FileHistoryLog vl ON vl.file.id = f.id AND vl.eventType = 'PASTE_VIEW'
                WHERE f.paste = true
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM FileEntity f WHERE f.paste = true")
    Page<PasteEntityView> findPastesWithViewCounts(Pageable pageable);

    /**
     * Search variant of {@link #findPastesWithViewCounts} filtered by a query string.
     *
     * @param query    search string (case-insensitive, partial-match on name, UUID)
     * @param pageable pagination parameters
     * @return matching page of {@link PasteEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.PasteEntityView(
                    f,
                    CAST(SUM(CASE WHEN vl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM FileEntity f
                LEFT JOIN FileHistoryLog vl ON vl.file.id = f.id AND vl.eventType = 'PASTE_VIEW'
                WHERE f.paste = true
                    AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))
                GROUP BY f
                ORDER BY f.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(f) FROM FileEntity f WHERE f.paste = true AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<PasteEntityView> searchPastesWithViewCounts(@Param("searchString") String query, Pageable pageable);
}
