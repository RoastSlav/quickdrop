package org.rostislav.quickdrop.service;

/**
 * Thrown by {@link ReputationProvider#isMalicious} when a check genuinely couldn't run (feed
 * never loaded, API call failed) — distinct from a completed check that found no match.
 * {@code reputationFailClosed} applies only to this, never to a clean verdict.
 */
public class ReputationCheckException extends Exception {
    public ReputationCheckException(String message) {
        super(message);
    }

    public ReputationCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
