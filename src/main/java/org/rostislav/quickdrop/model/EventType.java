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

    /**
     * A new file was successfully uploaded.
     */
    UPLOAD(EventCategory.FILE),

    /**
     * A file was downloaded by a user or via a share token.
     */
    DOWNLOAD(EventCategory.FILE),

    /**
     * A file's expiry date was reset (lifetime renewed).
     */
    RENEWAL(EventCategory.FILE),

    /**
     * A file was soft-deleted (record kept, physical file removed).
     */
    DELETION(EventCategory.FILE),

    // -------------------------------------------------------------------------
    // PASTE events
    // -------------------------------------------------------------------------

    /**
     * A new paste was created.
     */
    PASTE_CREATE(EventCategory.PASTE),

    /**
     * A paste was viewed.
     */
    PASTE_VIEW(EventCategory.PASTE),

    /**
     * An existing paste was edited.
     */
    PASTE_EDIT(EventCategory.PASTE),

    // -------------------------------------------------------------------------
    // SHARE events
    // -------------------------------------------------------------------------

    /**
     * A share token was generated for a file.
     */
    SHARE_CREATE(EventCategory.SHARE),

    /**
     * A file was downloaded via a share token.
     */
    SHARE_DOWNLOAD(EventCategory.SHARE),

    /**
     * A share token was removed by the scheduled cleanup (expired or download-limit exhausted).
     */
    SHARE_EXPIRE(EventCategory.SHARE),

    /**
     * A share token was manually revoked by an admin.
     */
    SHARE_REVOKE(EventCategory.SHARE),

    // -------------------------------------------------------------------------
    // SHORTLINK events (redirect links -- the general URL shortener)
    // -------------------------------------------------------------------------

    /**
     * A redirect (URL-shortener) link was created.
     */
    SHORTLINK_CREATE(EventCategory.SHORTLINK),

    /**
     * A short link (redirect or upload-share) was resolved by a visitor.
     */
    SHORTLINK_VISIT(EventCategory.SHORTLINK),

    /**
     * A redirect link was manually revoked by an admin.
     */
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

    /**
     * An admin successfully authenticated.
     */
    ADMIN_LOGIN(EventCategory.ADMIN),

    /**
     * An admin authentication attempt failed (wrong password).
     */
    ADMIN_LOGIN_FAIL(EventCategory.ADMIN),

    /**
     * An admin session was explicitly logged out.
     */
    ADMIN_LOGOUT(EventCategory.ADMIN),

    /**
     * An admin session expired due to inactivity (server-side session timeout).
     */
    ADMIN_SESSION_EXPIRE(EventCategory.ADMIN),

    /**
     * The admin saved application settings.
     */
    ADMIN_SETTINGS_CHANGE(EventCategory.ADMIN),

    /**
     * A database backup was created, scheduled or on-demand.
     */
    BACKUP_CREATED(EventCategory.ADMIN),

    /**
     * An admin uploaded an external file to use as a backup.
     */
    BACKUP_UPLOADED(EventCategory.ADMIN),

    /**
     * A database backup was restored, replacing the live database.
     */
    BACKUP_RESTORED(EventCategory.ADMIN),

    /**
     * A backup or restore operation failed.
     */
    BACKUP_FAILED(EventCategory.ADMIN),

    // -------------------------------------------------------------------------
    // SYSTEM events
    // -------------------------------------------------------------------------

    /**
     * The application started successfully.
     */
    STARTUP(EventCategory.SYSTEM),

    /**
     * The application is shutting down.
     */
    SHUTDOWN(EventCategory.SYSTEM),

    /**
     * The active storage backend became unreachable.
     */
    STORAGE_BACKEND_DOWN(EventCategory.SYSTEM),

    /**
     * The active storage backend recovered and is reachable again.
     */
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
