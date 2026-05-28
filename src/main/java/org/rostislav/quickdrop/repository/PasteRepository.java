package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.model.PasteEntityView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link Paste} with custom queries for listing, searching,
 * and analytics aggregation.
 *
 * <p>Queries that span both subtypes (orphan scan, deletion eligibility) live in
 * {@link UploadRepository}.  File-specific queries live in {@link FileRepository}.
 */
public interface PasteRepository extends JpaRepository<Paste, Long> {

    /**
     * Looks up a paste by its UUID path segment.
     * Returns soft-deleted records so admin controllers can still find them.
     *
     * @param uuid the paste's unique identifier
     * @return the matching entity, or empty if not found (or if the UUID belongs to a file)
     */
    @Query("SELECT p FROM Paste p WHERE p.uuid = :uuid")
    Optional<Paste> findByUUID(@Param("uuid") String uuid);

    /**
     * Returns the total number of live (non-deleted) paste entries.
     */
    @Query("SELECT COUNT(p) FROM Paste p WHERE p.deleted = false")
    long countPastes();

    /**
     * Returns the average byte length of live paste entries.
     *
     * @return average paste size in bytes, or {@code null} if there are no pastes
     */
    @Query("SELECT AVG(p.size) FROM Paste p WHERE p.deleted = false")
    Double averagePasteLength();

    /**
     * Returns the number of live paste entries whose name ends with {@code .md} (Markdown).
     */
    @Query("SELECT COUNT(p) FROM Paste p WHERE p.deleted = false AND p.name LIKE '%.md'")
    long countMarkdownPastes();

    /**
     * Returns a paginated list of all live paste entries with their total view counts.
     *
     * @param pageable pagination parameters
     * @return page of {@link PasteEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.PasteEntityView(
                    p,
                    CAST(SUM(CASE WHEN vl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM Paste p
                LEFT JOIN ActivityLog vl ON vl.file.id = p.id AND vl.eventType = 'PASTE_VIEW'
                WHERE p.deleted = false
                GROUP BY p
                ORDER BY p.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Paste p WHERE p.deleted = false")
    Page<PasteEntityView> findPastesWithViewCounts(Pageable pageable);

    /**
     * Search variant of {@link #findPastesWithViewCounts} filtered by a query string.
     *
     * @param query    search string (case-insensitive, partial-match on name and UUID)
     * @param pageable pagination parameters
     * @return matching page of {@link PasteEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.PasteEntityView(
                    p,
                    CAST(SUM(CASE WHEN vl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM Paste p
                LEFT JOIN ActivityLog vl ON vl.file.id = p.id AND vl.eventType = 'PASTE_VIEW'
                WHERE p.deleted = false
                    AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(p.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))
                GROUP BY p
                ORDER BY p.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Paste p WHERE p.deleted = false " +
                    "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(p.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<PasteEntityView> searchPastesWithViewCounts(@Param("searchString") String query, Pageable pageable);

    /**
     * Returns a paginated list of soft-deleted paste entries with their total view counts.
     *
     * @param pageable pagination parameters
     * @return page of {@link PasteEntityView} projections for deleted pastes
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.PasteEntityView(
                    p,
                    CAST(SUM(CASE WHEN vl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM Paste p
                LEFT JOIN ActivityLog vl ON vl.file.id = p.id AND vl.eventType = 'PASTE_VIEW'
                WHERE p.deleted = true
                GROUP BY p
                ORDER BY p.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Paste p WHERE p.deleted = true")
    Page<PasteEntityView> findDeletedPastesWithViewCounts(Pageable pageable);

    /**
     * Search variant of {@link #findDeletedPastesWithViewCounts} filtered by a query string.
     *
     * @param query    search string (case-insensitive, partial-match on name and UUID)
     * @param pageable pagination parameters
     * @return matching page of deleted {@link PasteEntityView} projections
     */
    @Query(value = """
                SELECT new org.rostislav.quickdrop.model.PasteEntityView(
                    p,
                    CAST(SUM(CASE WHEN vl.id IS NOT NULL THEN 1 ELSE 0 END) AS long)
                )
                FROM Paste p
                LEFT JOIN ActivityLog vl ON vl.file.id = p.id AND vl.eventType = 'PASTE_VIEW'
                WHERE p.deleted = true
                    AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :searchString, '%'))
                    OR LOWER(p.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))
                GROUP BY p
                ORDER BY p.uploadDate DESC
            """,
            countQuery = "SELECT COUNT(p) FROM Paste p WHERE p.deleted = true " +
                    "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :searchString, '%')) " +
                    "OR LOWER(p.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<PasteEntityView> searchDeletedPastesWithViewCounts(@Param("searchString") String query, Pageable pageable);
}
