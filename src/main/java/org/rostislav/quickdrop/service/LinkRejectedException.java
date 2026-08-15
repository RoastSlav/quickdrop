package org.rostislav.quickdrop.service;

/**
 * Thrown by {@link ShortLinkService#createRedirectLink} when {@link LinkGuard} blocks a
 * destination, or a requested custom alias is invalid/taken. Carries the same machine
 * reason code and user-safe message as {@link org.rostislav.quickdrop.model.LinkVerdict}.
 */
public class LinkRejectedException extends RuntimeException {
    private final String reasonCode;

    public LinkRejectedException(String reasonCode, String userMessage) {
        super(userMessage);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
