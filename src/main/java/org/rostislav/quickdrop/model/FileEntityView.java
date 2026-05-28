package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.Upload;

import java.time.LocalDate;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

/**
 * Read-only projection of a {@link org.rostislav.quickdrop.entity.StoredFile} used
 * in file-listing views.
 *
 * <p>The raw {@code size} bytes are converted to a human-readable string at
 * construction time. The {@code totalDownloads} count is injected from the
 * repository JOIN query.
 */
public class FileEntityView {
    public Long id;
    public String name;
    public String uuid;
    public String description;

    /**
     * Human-readable file size (e.g. "1.23 MB").
     */
    public String size;

    public boolean keepIndefinitely;
    public LocalDate uploadDate;

    /**
     * Total number of DOWNLOAD events logged for this file.
     */
    public long totalDownloads;

    public boolean hidden;

    /**
     * {@code true} when the file has a password hash set.
     */
    public boolean passwordProtected;

    /**
     * {@code true} when the file has been soft-deleted (physical file no longer on disk).
     */
    public boolean deleted;

    public FileEntityView() {
    }

    /**
     * @param upload         the source entity (a {@link org.rostislav.quickdrop.entity.StoredFile}
     *                       or any other {@link Upload} subtype)
     * @param totalDownloads pre-aggregated download count (from the repository JOIN)
     */
    public FileEntityView(Upload upload, long totalDownloads) {
        this.id = upload.id;
        this.name = upload.name;
        this.uuid = upload.uuid;
        this.description = upload.description;
        this.size = formatFileSize(upload.size);
        this.keepIndefinitely = upload.keepIndefinitely;
        this.uploadDate = upload.uploadDate;
        this.totalDownloads = totalDownloads;
        this.hidden = upload.hidden;
        this.passwordProtected = upload.passwordHash != null;
        this.deleted = upload.deleted;
    }
}
