package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.FileEntity;

import java.time.LocalDate;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

/**
 * Read-only projection of a {@link FileEntity} used in file-listing views.
 *
 * <p>The raw {@code size} bytes are converted to a human-readable string at
 * construction time. The {@code totalDownloads} count is injected from the
 * repository JOIN query so that no additional per-row queries are needed.
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

    /** Total number of DOWNLOAD events logged for this file. */
    public long totalDownloads;

    public boolean hidden;

    /** {@code true} when the file has a password hash set. */
    public boolean passwordProtected;

    public FileEntityView() {
    }

    /**
     * @param fileEntity     the source entity
     * @param totalDownloads pre-aggregated download count (from the repository JOIN)
     */
    public FileEntityView(FileEntity fileEntity, long totalDownloads) {
        this.id = fileEntity.id;
        this.name = fileEntity.name;
        this.uuid = fileEntity.uuid;
        this.description = fileEntity.description;
        this.size = formatFileSize(fileEntity.size);
        this.keepIndefinitely = fileEntity.keepIndefinitely;
        this.uploadDate = fileEntity.uploadDate;
        this.totalDownloads = totalDownloads;
        this.hidden = fileEntity.hidden;
        this.passwordProtected = fileEntity.passwordHash != null;
    }
}
