package org.rostislav.quickdrop.service;

/**
 * Thrown by {@link ReputationProvider#isMalicious} when a check genuinely could not be
 * performed — feed never successfully loaded, or an API call failed — as opposed to a
 * completed check that simply found no match. {@link ReputationCheckService} treats the two
 * outcomes very differently: a completed "not found" is a clean verdict regardless of any
 * setting, while this exception is what {@code reputationFailClosed} applies to.
 */
public class ReputationCheckException extends Exception {
    public ReputationCheckException(String message) {
        super(message);
    }

    public ReputationCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
