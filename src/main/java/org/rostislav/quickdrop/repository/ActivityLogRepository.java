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

    long countByEventType(EventType eventType);

    /**
     * Aggregates download counts that span multiple event types (e.g. {@code DOWNLOAD} and
     * {@code SHARE_DOWNLOAD}).
     */
    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.eventType IN :types")
    long countByEventTypeIn(@Param("types") Collection<EventType> types);

    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.file.uuid = :uuid AND h.eventType = :eventType")
    long countByFileAndType(@Param("uuid") String uuid, @Param("eventType") EventType eventType);

    /** Aggregates per-file download counts across {@code DOWNLOAD} and {@code SHARE_DOWNLOAD}. */
    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.file.uuid = :uuid AND h.eventType IN :types")
    long countByFileAndTypeIn(@Param("uuid") String uuid, @Param("types") Collection<EventType> types);

    @Query("SELECT h FROM ActivityLog h WHERE h.file.uuid = :uuid ORDER BY h.eventDate DESC")
    List<ActivityLog> findByFileUuidOrderByEventDateDesc(@Param("uuid") String uuid);

    /** Lets the sweep skip opening an archive for a category with nothing to purge. */
    @Query("SELECT COUNT(h) FROM ActivityLog h WHERE h.eventType IN :types AND h.eventDate < :cutoff")
    long countExpiring(@Param("types") Collection<EventType> types, @Param("cutoff") LocalDateTime cutoff);

    /**
     * Fetches one batch of rows to archive, oldest first. Fetches associations eagerly because
     * the sweep serialises them after the transaction ends, where a proxy would fail.
     */
    @Query("SELECT h FROM ActivityLog h LEFT JOIN FETCH h.file LEFT JOIN FETCH h.shortLink " +
            "WHERE h.eventType IN :types AND h.eventDate < :cutoff ORDER BY h.eventDate ASC, h.id ASC")
    List<ActivityLog> findExpiringBatch(@Param("types") Collection<EventType> types,
                                        @Param("cutoff") LocalDateTime cutoff,
                                        Pageable pageable);

    /**
     * Filters the activity log with optional date range, event types, IP/UA substrings, and a
     * source-type discriminator ({@code "file"}, {@code "paste"}, {@code "link"}, {@code "system"},
     * or {@code null} for all).
     *
     * <p>{@code eventTypes} must be non-empty -- a collection param can't be null-checked in
     * JPQL, so callers with no type filter pass every {@link EventType} instead of {@code null}.
     */
    @Query(value = "SELECT h FROM ActivityLog h LEFT JOIN FETCH h.file f WHERE " +
            "(:startDate IS NULL OR h.eventDate >= :startDate) AND " +
            "(:endDate IS NULL OR h.eventDate <= :endDate) AND " +
            "h.eventType IN :eventTypes AND " +
            "(:ip IS NULL OR LOWER(h.ipAddress) LIKE LOWER(CONCAT('%', :ip, '%'))) AND " +
            "(:ua IS NULL OR LOWER(h.userAgent) LIKE LOWER(CONCAT('%', :ua, '%'))) AND " +
            "(:sourceType IS NULL OR " +
            " (:sourceType = 'system' AND h.file IS NULL AND h.shortLink IS NULL) OR " +
            " (:sourceType = 'link'   AND h.shortLink IS NOT NULL) OR " +
            " (:sourceType = 'file'   AND TYPE(f) = StoredFile) OR " +
            " (:sourceType = 'paste'  AND TYPE(f) = Paste)) " +
            "ORDER BY h.eventDate DESC, h.id DESC",
            // The join has to be spelled out and left: navigating h.file inline makes Hibernate
            // add an inner join to the count query, which then silently drops every row without
            // an upload (admin, system and short-link events) from the total.
            countQuery = "SELECT COUNT(h) FROM ActivityLog h LEFT JOIN h.file f WHERE " +
                    "(:startDate IS NULL OR h.eventDate >= :startDate) AND " +
                    "(:endDate IS NULL OR h.eventDate <= :endDate) AND " +
                    "h.eventType IN :eventTypes AND " +
                    "(:ip IS NULL OR LOWER(h.ipAddress) LIKE LOWER(CONCAT('%', :ip, '%'))) AND " +
                    "(:ua IS NULL OR LOWER(h.userAgent) LIKE LOWER(CONCAT('%', :ua, '%'))) AND " +
                    "(:sourceType IS NULL OR " +
                    " (:sourceType = 'system' AND h.file IS NULL AND h.shortLink IS NULL) OR " +
                    " (:sourceType = 'link'   AND h.shortLink IS NOT NULL) OR " +
                    " (:sourceType = 'file'   AND TYPE(f) = StoredFile) OR " +
                    " (:sourceType = 'paste'  AND TYPE(f) = Paste))")
    Page<ActivityLog> findFiltered(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("eventTypes") Collection<EventType> eventTypes,
            @Param("ip") String ip,
            @Param("ua") String ua,
            @Param("sourceType") String sourceType,
            Pageable pageable);
}
