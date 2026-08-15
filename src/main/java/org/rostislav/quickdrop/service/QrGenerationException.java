package org.rostislav.quickdrop.service;

/** Thrown by {@link QrCodeService} when content can't be encoded as a QR code. */
public class QrGenerationException extends RuntimeException {
    public QrGenerationException(String message) {
        super(message);
    }
}
