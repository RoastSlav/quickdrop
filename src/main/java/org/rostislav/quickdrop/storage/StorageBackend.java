package org.rostislav.quickdrop.storage;

/** The active file storage backend. */
public enum StorageBackend {
    LOCAL,
    S3,
    AZURE,
    SFTP,
    WEBDAV
}
