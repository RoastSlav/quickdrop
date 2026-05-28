package org.rostislav.quickdrop.model;

/**
 * Broad category that an {@link EventType} event belongs to.
 *
 * <p>Used by templates and filter logic instead of fragile
 * {@code name().startsWith("ADMIN")} / {@code name() == "STARTUP"} hacks.
 */
public enum EventCategory {
    /**
     * Events directly tied to a regular (non-paste) file.
     */
    FILE,
    /**
     * Events tied to a text paste.
     */
    PASTE,
    /**
     * Events involving share tokens.
     */
    SHARE,
    /**
     * Admin authentication and configuration events.
     */
    ADMIN,
    /**
     * Application-level lifecycle events (not tied to any file).
     */
    SYSTEM
}
