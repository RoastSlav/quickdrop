package org.rostislav.quickdrop.model;

/**
 * Result of a {@link org.rostislav.quickdrop.service.LinkGuard} check: either the
 * destination is allowed (with its normalized/absolute form), or it's blocked with a
 * machine-readable reason code and a plain-language message safe to show the user.
 */
public record LinkVerdict(boolean allowed, String normalizedUrl, String reasonCode, String userMessage) {
    public static LinkVerdict allow(String normalizedUrl) {
        return new LinkVerdict(true, normalizedUrl, null, null);
    }

    public static LinkVerdict block(String reasonCode, String userMessage) {
        return new LinkVerdict(false, null, reasonCode, userMessage);
    }
}
