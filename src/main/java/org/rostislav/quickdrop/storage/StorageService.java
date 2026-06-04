package org.rostislav.quickdrop.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Backend-agnostic file storage abstraction.
 *
 * <p>Keys are plain string identifiers (typically a file UUID, or a UUID plus a suffix
 * such as {@code {uuid}-share-{token}}). The implementation decides where and how bytes
 * are persisted.
 *
 * <p>Streams returned by {@link #getInputStream} and {@link #getOutputStream} must be
 * closed by the caller. {@link #getOutputStream} must be closed <em>successfully</em>
 * to commit the data — an abrupt close after an exception may leave partial data.
 */
public interface StorageService {

    /**
     * Opens an input stream for reading the object identified by {@code key}.
     *
     * @throws IOException if the object does not exist or cannot be opened
     */
    InputStream getInputStream(String key) throws IOException;

    /**
     * Opens an output stream for writing a new object identified by {@code key}.
     *
     * <p>The write is committed when the stream is closed normally. If the stream is
     * closed after an exception, the implementation makes a best effort to clean up
     * any partial data.
     *
     * @throws IOException if the object cannot be created
     */
    OutputStream getOutputStream(String key) throws IOException;

    /** Returns {@code true} if an object with the given key exists. */
    boolean exists(String key);

    /**
     * Deletes the object identified by {@code key}.
     *
     * @return {@code true} if the object was deleted or was already absent; {@code false} on error
     */
    boolean delete(String key);

    /**
     * Lists all object keys whose name ends with the given suffix.
     * Used by cleanup jobs to find legacy sidecar files.
     */
    List<String> listKeySuffix(String suffix);

    /** Returns the active storage backend type. */
    StorageBackend getBackend();
}
