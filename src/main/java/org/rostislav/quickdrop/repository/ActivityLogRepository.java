package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.model.EventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link ActivityLog} audit records.
 *
 * <p>Provides analytics queries (counts per event type, per file) used by the
 * admin dashboard, the per-file history page, and the global activity log.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /**
     * Returns the total number of events of a given type across all files.
     *
     * @param eventType the event type to count
     * @return total event count
     */
    long countByEventType(EventType eventType);

    /**
     * Returns the total number of events whose type is any of the given values.
     * Used to aggregate download counts that span multiple event types
     * (e.g. {@code DOWNLOAD} and {@code SHARE_DOWNLOAD}).
     *
     * @param types the event types to include in the count
     * @return total event count across all provided types
     */
    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.eventType IN :types")
    long countByEventTypeIn(@Param("types") Collection<EventType> types);

    /**
     * Returns the number of events of a given type recorded against a specific file.
     *
     * @param uuid      UUID of the file
     * @param eventType the event type to count
     * @return per-file event count
     */
    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.file.uuid = :uuid AND h.eventType = :eventType")
    long countByFileAndType(@Param("uuid") String uuid, @Param("eventType") EventType eventType);

    /**
     * Returns the number of events whose type is any of the given values for a specific file.
     * Used to aggregate per-file download counts across {@code DOWNLOAD} and {@code SHARE_DOWNLOAD}.
     *
     * @param uuid  UUID of the file
     * @param types the event types to include
     * @return per-file event count across all provided types
     */
    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.file.uuid = :uuid AND h.eventType IN :types")
    long countByFileAndTypeIn(@Param("uuid") String uuid, @Param("types") Collection<EventType> types);

    /**
     * Returns all activity entries for a given file UUID, most recent first.
     *
     * @param uuid UUID of the file
     * @return ordered list of log entries
     */
    @Query("SELECT h FROM ActivityLog h WHERE h.file.uuid = :uuid ORDER BY h.eventDate DESC")
    List<ActivityLog> findByFileUuidOrderByEventDateDesc(@Param("uuid") String uuid);

    /**
     * Filters the activity log with optional date range, event type, IP/UA substrings,
     * and a source-type discriminator.
     *
     * @param startDate  lower bound on event timestamp, or {@code null}
     * @param endDate    upper bound on event timestamp, or {@code null}
     * @param eventType  exact event type, or {@code null}
     * @param ip         IP substring filter, or {@code null}
     * @param ua         user-agent substring filter, or {@code null}
     * @param sourceType one of {@code "file"} ({@link org.rostislav.quickdrop.entity.StoredFile}),
     *                   {@code "paste"} ({@link org.rostislav.quickdrop.entity.Paste}),
     *                   {@code "system"} (no associated upload), or {@code null} for all
     * @param pageable   pagination and sort configuration
     */
    @Query(value = "SELECT h FROM ActivityLog h LEFT JOIN h.file WHERE " +
            "(:startDate IS NULL OR h.eventDate >= :startDate) AND " +
            "(:endDate IS NULL OR h.eventDate <= :endDate) AND " +
            "(:eventType IS NULL OR h.eventType = :eventType) AND " +
            "(:ip IS NULL OR LOWER(h.ipAddress) LIKE LOWER(CONCAT('%', :ip, '%'))) AND " +
            "(:ua IS NULL OR LOWER(h.userAgent) LIKE LOWER(CONCAT('%', :ua, '%'))) AND " +
            "(:sourceType IS NULL OR " +
            " (:sourceType = 'system' AND h.file IS NULL) OR " +
            " (:sourceType = 'file'   AND h.file IS NOT NULL AND TYPE(h.file) = StoredFile) OR " +
            " (:sourceType = 'paste'  AND h.file IS NOT NULL AND TYPE(h.file) = Paste)) " +
            "ORDER BY h.eventDate DESC",
            countQuery = "SELECT COUNT(h) FROM ActivityLog h WHERE " +
                    "(:startDate IS NULL OR h.eventDate >= :startDate) AND " +
                    "(:endDate IS NULL OR h.eventDate <= :endDate) AND " +
                    "(:eventType IS NULL OR h.eventType = :eventType) AND " +
                    "(:ip IS NULL OR LOWER(h.ipAddress) LIKE LOWER(CONCAT('%', :ip, '%'))) AND " +
                    "(:ua IS NULL OR LOWER(h.userAgent) LIKE LOWER(CONCAT('%', :ua, '%'))) AND " +
                    "(:sourceType IS NULL OR " +
                    " (:sourceType = 'system' AND h.file IS NULL) OR " +
                    " (:sourceType = 'file'   AND h.file IS NOT NULL AND TYPE(h.file) = StoredFile) OR " +
                    " (:sourceType = 'paste'  AND h.file IS NOT NULL AND TYPE(h.file) = Paste))")
    Page<ActivityLog> findFiltered(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("eventType") EventType eventType,
            @Param("ip") String ip,
            @Param("ua") String ua,
            @Param("sourceType") String sourceType,
            Pageable pageable);
}
