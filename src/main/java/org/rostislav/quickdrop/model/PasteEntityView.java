package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.FileEntity;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Read-only projection of a paste {@link FileEntity} used in paste-listing views.
 *
 * <p>Whether the paste is Markdown is determined by checking whether the stored
 * filename ends with {@code .md}. The {@code totalViews} count is injected from
 * the repository JOIN query so that no additional per-row queries are needed.
 */
public class PasteEntityView {
    public String uuid;
    public String name;
    public LocalDate uploadDate;

    /**
     * {@code true} when the paste filename ends with {@code .md}.
     */
    public boolean isMarkdown;

    /**
     * Human-readable size string (currently "N B" — raw bytes).
     */
    public String size;

    /**
     * Raw byte length of the paste content.
     */
    public long rawSize;

    /**
     * {@code true} when the paste has a password hash set.
     */
    public boolean passwordProtected;

    /**
     * Total number of PASTE_VIEW events logged for this paste.
     */
    public long totalViews;

    public PasteEntityView() {
    }

    /**
     * @param fileEntity the source paste entity
     * @param totalViews pre-aggregated view count (from the repository JOIN)
     */
    public PasteEntityView(FileEntity fileEntity, long totalViews) {
        this.uuid = fileEntity.uuid;
        this.name = fileEntity.name;
        this.uploadDate = fileEntity.uploadDate;
        this.isMarkdown = fileEntity.name != null && fileEntity.name.toLowerCase(Locale.ROOT).endsWith(".md");
        this.rawSize = fileEntity.size;
        this.size = fileEntity.size + " B";
        this.passwordProtected = fileEntity.passwordHash != null;
        this.totalViews = totalViews;
    }
}
