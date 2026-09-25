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

    /** Returns soft-deleted records too, so admin controllers can still find them. */
    @Query("SELECT p FROM Paste p WHERE p.uuid = :uuid")
    Optional<Paste> findByUUID(@Param("uuid") String uuid);

    @Query("SELECT COUNT(p) FROM Paste p WHERE p.deleted = false")
    long countPastes();

    /** {@code null} if there are no pastes. */
    @Query("SELECT AVG(p.size) FROM Paste p WHERE p.deleted = false")
    Double averagePasteLength();

    @Query("SELECT COUNT(p) FROM Paste p WHERE p.deleted = false AND p.name LIKE '%.md'")
    long countMarkdownPastes();

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

    /** Search variant of {@link #findPastesWithViewCounts}. */
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

    /** Search variant of {@link #findDeletedPastesWithViewCounts}. */
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
