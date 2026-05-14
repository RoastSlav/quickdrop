package org.rostislav.quickdrop.repository;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link FileHistoryLog} audit records.
 *
 * <p>Provides analytics queries (counts per event type, per file) and a bulk-delete
 * operation used when a file is purged from the system.
 */
public interface FileHistoryLogRepository extends JpaRepository<FileHistoryLog, Long> {

    /**
     * Returns the total number of events of a given type across all files.
     *
     * @param eventType the event category to count
     * @return total event count
     */
    long countByEventType(FileHistoryType eventType);

    /**
     * Returns the total number of events whose type is any of the given values.
     * Used to aggregate download counts that span multiple event types
     * (e.g. {@code DOWNLOAD} and {@code SHARE_DOWNLOAD}).
     *
     * @param types the event types to include in the count
     * @return total event count across all provided types
     */
    @Query("SELECT COUNT(h) FROM FileHistoryLog h WHERE h.eventType IN :types")
    long countByEventTypeIn(@Param("types") Collection<FileHistoryType> types);

    /**
     * Returns the number of events of a given type recorded against a specific file.
     *
     * @param uuid      UUID of the file
     * @param eventType the event category to count
     * @return per-file event count
     */
    @Query("SELECT COUNT(fhl) FROM FileHistoryLog fhl WHERE fhl.file.uuid = :uuid AND fhl.eventType = :eventType")
    long countByFileAndType(String uuid, FileHistoryType eventType);

    /**
     * Returns the number of events whose type is any of the given values for a specific file.
     * Used to aggregate per-file download counts across {@code DOWNLOAD} and {@code SHARE_DOWNLOAD}.
     *
     * @param uuid  UUID of the file
     * @param types the event types to include
     * @return per-file event count across all provided types
     */
    @Query("SELECT COUNT(h) FROM FileHistoryLog h WHERE h.file.uuid = :uuid AND h.eventType IN :types")
    long countByFileAndTypeIn(@Param("uuid") String uuid, @Param("types") Collection<FileHistoryType> types);

    /**
     * Returns all history entries for a given file UUID, most recent first.
     *
     * @param uuid UUID of the file
     * @return ordered list of log entries
     */
    @Query("SELECT fhl FROM FileHistoryLog fhl WHERE fhl.file.uuid = :uuid ORDER BY fhl.eventDate DESC")
    List<FileHistoryLog> findByFileUuidOrderByEventDateDesc(String uuid);

    /**
     * Deletes all log entries associated with the given file ID.
     * Must be called before deleting the parent {@link org.rostislav.quickdrop.entity.FileEntity}
     * to satisfy the foreign-key constraint.
     *
     * @param fileId database ID of the file whose history should be cleared
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM FileHistoryLog fhl WHERE fhl.file.id = :fileId")
    void deleteByFileId(Long fileId);

    /**
     * Returns a filtered, paginated slice of the global activity log.
     * Any parameter that is {@code null} is treated as "no filter on this dimension".
     *
     * @param startDate lower bound on event timestamp (inclusive), or {@code null}
     * @param endDate   upper bound on event timestamp (inclusive), or {@code null}
     * @param eventType exact event type filter, or {@code null} to include all types
     * @param ip        substring filter on IP address (case-insensitive), or {@code null}
     * @param ua        substring filter on user-agent (case-insensitive), or {@code null}
     * @param pageable  pagination and sort configuration
     * @return a page of matching log entries ordered by event date descending
     */
    @Query(value = "SELECT h FROM FileHistoryLog h JOIN FETCH h.file WHERE " +
            "(:startDate IS NULL OR h.eventDate >= :startDate) AND " +
            "(:endDate IS NULL OR h.eventDate <= :endDate) AND " +
            "(:eventType IS NULL OR h.eventType = :eventType) AND " +
            "(:ip IS NULL OR LOWER(h.ipAddress) LIKE LOWER(CONCAT('%', :ip, '%'))) AND " +
            "(:ua IS NULL OR LOWER(h.userAgent) LIKE LOWER(CONCAT('%', :ua, '%'))) " +
            "ORDER BY h.eventDate DESC",
            countQuery = "SELECT COUNT(h) FROM FileHistoryLog h WHERE " +
                    "(:startDate IS NULL OR h.eventDate >= :startDate) AND " +
                    "(:endDate IS NULL OR h.eventDate <= :endDate) AND " +
                    "(:eventType IS NULL OR h.eventType = :eventType) AND " +
                    "(:ip IS NULL OR LOWER(h.ipAddress) LIKE LOWER(CONCAT('%', :ip, '%'))) AND " +
                    "(:ua IS NULL OR LOWER(h.userAgent) LIKE LOWER(CONCAT('%', :ua, '%')))")
    Page<FileHistoryLog> findFiltered(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("eventType") FileHistoryType eventType,
            @Param("ip") String ip,
            @Param("ua") String ua,
            Pageable pageable);
}
