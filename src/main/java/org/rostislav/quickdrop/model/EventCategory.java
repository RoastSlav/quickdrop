package org.rostislav.quickdrop.model;

/**
 * Broad category that an {@link EventType} event belongs to.
 *
 * <p>Used by templates and filter logic instead of fragile
 * {@code name().startsWith("ADMIN")} / {@code name() == "STARTUP"} hacks.
 */
public enum EventCategory {
    FILE,
    PASTE,
    SHARE,
    /**
     * General-purpose short links (redirect links, {@code /s/{code}}), not upload-share tokens.
     */
    SHORTLINK,
    ADMIN,
    SYSTEM
}
