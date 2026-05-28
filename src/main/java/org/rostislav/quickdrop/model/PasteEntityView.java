package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.Upload;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Read-only projection of a {@link org.rostislav.quickdrop.entity.Paste} used in
 * paste-listing views.
 *
 * <p>Whether the paste is Markdown is determined by checking whether the stored
 * filename ends with {@code .md}. The {@code totalViews} count is injected from
 * the repository JOIN query.
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

    /**
     * {@code true} when the paste has been soft-deleted.
     */
    public boolean deleted;

    public PasteEntityView() {
    }

    /**
     * @param upload     the source entity (a {@link org.rostislav.quickdrop.entity.Paste}
     *                   or any other {@link Upload} subtype)
     * @param totalViews pre-aggregated view count (from the repository JOIN)
     */
    public PasteEntityView(Upload upload, long totalViews) {
        this.uuid = upload.uuid;
        this.name = upload.name;
        this.uploadDate = upload.uploadDate;
        this.isMarkdown = upload.name != null && upload.name.toLowerCase(Locale.ROOT).endsWith(".md");
        this.rawSize = upload.size;
        this.size = upload.size + " B";
        this.passwordProtected = upload.passwordHash != null;
        this.totalViews = totalViews;
        this.deleted = upload.deleted;
    }
}
