package org.rostislav.quickdrop.model;

/**
 * Categorises the events that can be recorded in a {@link org.rostislav.quickdrop.entity.FileHistoryLog}.
 *
 * <p>Values are persisted as strings ({@code EnumType.STRING}) so that adding new
 * constants does not require a database migration.
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
    PASTE_EDIT
}
