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

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    @Query("SELECT f FROM FileEntity f WHERE f.uuid = :uuid")
    Optional<FileEntity> findByUUID(@Param("uuid") String uuid);

    @Query("SELECT f FROM FileEntity f WHERE f.keepIndefinitely = false AND f.uploadDate < :thresholdDate")
    List<FileEntity> getFilesForDeletion(@Param("thresholdDate") LocalDate thresholdDate);

    @Query("SELECT f FROM FileEntity f WHERE f.hidden = false AND f.paste = false ORDER BY f.uploadDate DESC")
    Page<FileEntity> findAllNotHiddenFiles(Pageable pageable);

    @Query("SELECT SUM(f.size) FROM FileEntity f")
    Long totalFileSizeForAllFiles();

    @Query("SELECT SUM(f.size) FROM FileEntity f WHERE f.paste = false")
    Long totalFileSizeForFilesOnly();

    long countByPasteFalse();

    long countByPasteTrue();

    @Query("SELECT AVG(f.size) FROM FileEntity f WHERE f.paste = true")
    Double averagePasteLength();

    @Query("SELECT COUNT(f) FROM FileEntity f WHERE f.paste = true AND f.name LIKE '%.md'")
    long countMarkdownPastes();

    @Query(value = "SELECT f FROM FileEntity f WHERE f.hidden = false AND f.paste = false AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%'))) ORDER BY f.uploadDate DESC",
            countQuery = "SELECT COUNT(f) FROM FileEntity f WHERE f.hidden = false AND f.paste = false AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    Page<FileEntity> searchNotHiddenFiles(@Param("searchString") String query, Pageable pageable);

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
