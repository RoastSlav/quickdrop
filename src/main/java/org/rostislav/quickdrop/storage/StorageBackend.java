package org.rostislav.quickdrop.storage;

/** The active file storage backend. */
public enum StorageBackend {
    /** Files are stored on the local filesystem under the configured storage path. */
    LOCAL,
    /** Files are stored in an S3 or S3-compatible object store. */
    S3
}
