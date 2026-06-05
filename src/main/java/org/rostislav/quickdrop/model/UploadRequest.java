package org.rostislav.quickdrop.model;

/**
 * Carries all metadata for a file or paste upload through the chunked-merge pipeline.
 *
 * <p>An instance is created in the REST controller for each upload and passed to
 * {@link org.rostislav.quickdrop.service.AsyncFileMergeService#submitChunk}.
 */
public class UploadRequest {
    /**
     * Unique identifier for this upload session, generated client-side (file uploads) or
     * server-side (paste uploads).  Used as the {@link AsyncFileMergeService} task key and
     * as the temp-file prefix so that concurrent uploads of files with the same name do not
     * collide and temp filenames cannot contain path-traversal characters from
     * user-supplied file names.
     */
    public String uploadId;

    /**
     * Original filename (or paste title with extension).
     */
    public String fileName;

    /**
     * Total number of chunks expected for this upload.
     */
    public int totalChunks;

    /**
     * Total file size in bytes as declared by the client.
     */
    public Long fileSize;

    /**
     * Optional human-readable description provided by the uploader.
     */
    public String description;

    /**
     * Whether the file should be exempt from the scheduled age-based deletion.
     */
    public boolean keepIndefinitely;

    /**
     * Cleartext access password; {@code null} or blank if no password is set.
     */
    public String password;

    /**
     * Whether the file should be hidden from the public listing.
     */
    public boolean hidden;

    /**
     * Resolved IP address of the uploader (from X-Forwarded-For or RemoteAddr).
     */
    public String uploaderIp;

    /**
     * User-Agent header value from the upload request.
     */
    public String uploaderUserAgent;

    /**
     * Whether this upload is a folder (ZIP archive with a manifest).
     */
    public boolean folderUpload;

    /**
     * Display name of the uploaded folder (only set when {@link #folderUpload} is {@code true}).
     */
    public String folderName;

    /**
     * JSON array describing the folder's file tree, sanitised for safe storage.
     * {@code null} for single-file uploads.
     */
    public String folderManifest;

    /**
     * {@code true} when this request represents a text paste rather than a binary file.
     */
    public boolean paste;

    /**
     * When {@code true} the paste password guards editing only; viewing is public.
     * Only meaningful when {@link #paste} is {@code true}.
     * Also suppresses AES encryption regardless of settings.
     */
    public boolean editOnly;

    /**
     * When {@code true} the paste is permanently immutable after the first save.
     * Only meaningful when {@link #paste} is {@code true}.
     */
    public boolean immutable;

    public UploadRequest(String description, boolean keepIndefinitely, String password, boolean hidden,
                         String fileName, int totalChunks, Long fileSize,
                         String uploaderIp, String uploaderUserAgent,
                         boolean folderUpload, String folderName, String folderManifest, boolean paste) {
        this(description, keepIndefinitely, password, hidden, fileName, totalChunks, fileSize,
                uploaderIp, uploaderUserAgent, folderUpload, folderName, folderManifest, paste, false, false);
    }

    public UploadRequest(String description, boolean keepIndefinitely, String password, boolean hidden,
                         String fileName, int totalChunks, Long fileSize,
                         String uploaderIp, String uploaderUserAgent,
                         boolean folderUpload, String folderName, String folderManifest, boolean paste,
                         boolean editOnly, boolean immutable) {
        this.description = description;
        this.keepIndefinitely = keepIndefinitely;
        this.password = password;
        this.hidden = hidden;
        this.fileName = fileName;
        this.totalChunks = totalChunks;
        this.fileSize = fileSize;
        this.uploaderIp = uploaderIp;
        this.uploaderUserAgent = uploaderUserAgent;
        this.folderUpload = folderUpload;
        this.folderName = folderName;
        this.folderManifest = folderManifest;
        this.paste = paste;
        this.editOnly = editOnly;
        this.immutable = immutable;
    }
}
