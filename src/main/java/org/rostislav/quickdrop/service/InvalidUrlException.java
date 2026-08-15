package org.rostislav.quickdrop.service;

/**
 * Thrown by {@link UrlNormalizationService} when user input can't be turned into a valid
 * absolute URL. The message is written to be shown directly to the user.
 */
public class InvalidUrlException extends RuntimeException {
    public InvalidUrlException(String message) {
        super(message);
    }
}
