package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.ActivityLog;

import java.time.LocalDateTime;

/**
 * Read-only projection of a single entry in a file's activity history,
 * shown on the per-file history page.
 *
 * @param actionType lowercase {@link EventType} name, matching the i18n message keys
 *                   (e.g. {@code "download"}, {@code "renewal"}, {@code "upload"})
 */
public record ActivityLogEntry(
        String actionType,
        LocalDateTime actionDate,
        String ipAddress,
        String userAgent
) {
    public ActivityLogEntry(ActivityLog log) {
        this(log.getEventType().name().toLowerCase(),
                log.getEventDate(),
                log.getIpAddress(),
                log.getUserAgent());
    }
}
