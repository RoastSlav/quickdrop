package org.rostislav.quickdrop.model;

/**
 * All event types that can be recorded in an {@link org.rostislav.quickdrop.entity.ActivityLog}.
 *
 * <p>Values are persisted as strings ({@code EnumType.STRING}).
 *
 * <p>Each value carries an {@link EventCategory} which groups related events and drives
 * UI badge colours and activity-log source filters.
 */
public enum EventType {

    // -------------------------------------------------------------------------
    // FILE events
    // -------------------------------------------------------------------------

    UPLOAD(EventCategory.FILE),
    DOWNLOAD(EventCategory.FILE),
    RENEWAL(EventCategory.FILE),

    /**
     * A file was soft-deleted (record kept, physical file removed).
     */
    DELETION(EventCategory.FILE),

    // -------------------------------------------------------------------------
    // PASTE events
    // -------------------------------------------------------------------------

    PASTE_CREATE(EventCategory.PASTE),
    PASTE_VIEW(EventCategory.PASTE),
    PASTE_EDIT(EventCategory.PASTE),

    // -------------------------------------------------------------------------
    // SHARE events
    // -------------------------------------------------------------------------

    SHARE_CREATE(EventCategory.SHARE),
    SHARE_DOWNLOAD(EventCategory.SHARE),

    /**
     * A share token was removed by the scheduled cleanup (expired or download-limit exhausted).
     */
    SHARE_EXPIRE(EventCategory.SHARE),

    SHARE_REVOKE(EventCategory.SHARE),

    // -------------------------------------------------------------------------
    // SHORTLINK events (redirect links -- the general URL shortener)
    // -------------------------------------------------------------------------

    SHORTLINK_CREATE(EventCategory.SHORTLINK),
    SHORTLINK_VISIT(EventCategory.SHORTLINK),
    SHORTLINK_REVOKE(EventCategory.SHORTLINK),

    /**
     * A redirect link was removed by the scheduled cleanup (expired or use-limit exhausted).
     */
    SHORTLINK_EXPIRE(EventCategory.SHORTLINK),

    /**
     * A short-link creation or resolve was blocked by {@link org.rostislav.quickdrop.service.LinkGuard}
     * (unsafe destination, private-network target, or a reputation-feed match).
     */
    SHORTLINK_BLOCKED(EventCategory.SHORTLINK),

    // -------------------------------------------------------------------------
    // ADMIN events
    // -------------------------------------------------------------------------

    ADMIN_LOGIN(EventCategory.ADMIN),
    ADMIN_LOGIN_FAIL(EventCategory.ADMIN),
    ADMIN_LOGOUT(EventCategory.ADMIN),

    /**
     * An admin session expired due to inactivity (server-side session timeout).
     */
    ADMIN_SESSION_EXPIRE(EventCategory.ADMIN),

    ADMIN_SETTINGS_CHANGE(EventCategory.ADMIN),
    BACKUP_CREATED(EventCategory.ADMIN),
    BACKUP_UPLOADED(EventCategory.ADMIN),
    BACKUP_RESTORED(EventCategory.ADMIN),
    BACKUP_FAILED(EventCategory.ADMIN),

    // -------------------------------------------------------------------------
    // SYSTEM events
    // -------------------------------------------------------------------------

    STARTUP(EventCategory.SYSTEM),
    SHUTDOWN(EventCategory.SYSTEM),
    STORAGE_BACKEND_DOWN(EventCategory.SYSTEM),
    STORAGE_BACKEND_UP(EventCategory.SYSTEM),

    /**
     * A threat-intelligence feed downloaded new content. Not recorded for a 304 or a skipped
     * sync, so the log shows content changes rather than every scheduler tick.
     */
    REPUTATION_FEED_UPDATED(EventCategory.SYSTEM),

    /**
     * A threat-intelligence feed refresh failed; the previously loaded copy keeps serving.
     */
    REPUTATION_FEED_FAILED(EventCategory.SYSTEM),

    /**
     * Expired activity-log entries were archived to storage and deleted from the table.
     */
    ACTIVITY_RETENTION_PURGED(EventCategory.SYSTEM),

    /**
     * A retention sweep could not archive a category, so nothing was deleted for it.
     */
    ACTIVITY_RETENTION_FAILED(EventCategory.SYSTEM);

    // -------------------------------------------------------------------------

    private final EventCategory category;

    EventType(EventCategory category) {
        this.category = category;
    }

    /**
     * Returns the broad category this event belongs to.
     *
     * @return non-null {@link EventCategory}
     */
    public EventCategory getCategory() {
        return category;
    }
}
