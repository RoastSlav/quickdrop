package org.rostislav.quickdrop.model;

/**
 * Categorises the events that can be recorded in a {@link org.rostislav.quickdrop.entity.FileHistoryLog}.
 *
 * <p>Values are persisted as strings ({@code EnumType.STRING}).
 */
public enum FileHistoryType {
    /**
     * A new file or paste was successfully uploaded.
     */
    UPLOAD,

    /** A file was downloaded by a user or via a share token. */
    DOWNLOAD,

    /** A file's expiry date was reset (lifetime renewed). */
    RENEWAL,

    /** A file was deleted from the filesystem and the database. */
    DELETION,

    /** A new paste was created. */
    PASTE_CREATE,

    /** A paste was viewed. */
    PASTE_VIEW,

    /** An existing paste was edited. */
    PASTE_EDIT,

    /**
     * A share token was generated for a file.
     */
    SHARE_CREATE,

    /**
     * A file was downloaded via a share token.
     */
    SHARE_DOWNLOAD,

    /**
     * A share token was removed by the scheduled cleanup (expired or download-limit exhausted).
     */
    SHARE_EXPIRE,

    /**
     * A share token was manually revoked by an admin.
     */
    SHARE_REVOKE
}
