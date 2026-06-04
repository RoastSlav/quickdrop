package org.rostislav.quickdrop.storage;

/** The active file storage backend. */
public enum StorageBackend {
    /** Files are stored on the local filesystem under the configured storage path. */
    LOCAL,
    /** Files are stored in an S3 or S3-compatible object store. */
    S3,
    /**
     * Files are stored in Azure Blob Storage.
     */
    AZURE,
    /**
     * Files are stored on a remote server via SFTP.
     */
    SFTP,
    /**
     * Files are stored on a WebDAV server (e.g. Nextcloud, ownCloud).
     */
    WEBDAV
}
