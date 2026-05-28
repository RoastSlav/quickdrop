package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.ActivityLog;

import java.time.LocalDateTime;

/**
 * Read-only projection of a single entry in a file's activity history,
 * shown on the per-file history page.
 *
 * <p>Created from an {@link ActivityLog} row via the
 * {@link #ActivityLogEntry(ActivityLog)} constructor, which maps the
 * {@link EventType} to a lowercase action-type key used in i18n messages.
 *
 * @param actionType lowercase action label derived from the {@link EventType} name
 *                   (e.g. {@code "download"}, {@code "renewal"}, {@code "upload"})
 * @param actionDate when the event occurred
 * @param ipAddress  requester IP address
 * @param userAgent  requester User-Agent header value
 */
public record ActivityLogEntry(
        String actionType,
        LocalDateTime actionDate,
        String ipAddress,
        String userAgent
) {
    /**
     * Builds an entry from a raw activity log row.
     * The {@link EventType} name is lowercased to match the i18n message keys.
     *
     * @param log the audit log entry
     */
    public ActivityLogEntry(ActivityLog log) {
        this(log.getEventType().name().toLowerCase(),
                log.getEventDate(),
                log.getIpAddress(),
                log.getUserAgent());
    }
}
