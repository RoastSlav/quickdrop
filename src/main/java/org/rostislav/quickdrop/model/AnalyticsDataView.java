package org.rostislav.quickdrop.model;

/**
 * Aggregated analytics snapshot displayed on the admin dashboard.
 *
 * <p>Computed and cached by {@link org.rostislav.quickdrop.service.AnalyticsService#getAnalytics()}.
 * All formatted size strings (e.g. "1.23 GB") are pre-formatted via
 * {@link org.rostislav.quickdrop.util.FileUtils#formatFileSize(long)}.
 */
public class AnalyticsDataView {
    /**
     * Total number of file download events recorded in the history log.
     */
    private long totalDownloads;

    /** Human-readable total storage consumed by all files (excludes pastes). */
    private String totalSpaceUsed;

    /** Human-readable average file size across non-paste uploads. */
    private String averageFileSize;

    /** Number of non-paste file entries in the database. */
    private long totalFileCount;

    /** Number of paste entries in the database. */
    private long totalPastes;

    /** Total number of PASTE_VIEW events recorded. */
    private long totalPasteViews;

    /** Human-readable average byte length of paste content. */
    private String averagePasteLength;

    /** Number of paste entries whose name ends with {@code .md}. */
    private long markdownPasteCount;

    /** Number of paste entries that are plain text (totalPastes − markdownPasteCount). */
    private long plainTextPasteCount;

    public long getTotalDownloads() {
        return totalDownloads;
    }

    public void setTotalDownloads(long totalDownloads) {
        this.totalDownloads = totalDownloads;
    }

    public String getTotalSpaceUsed() {
        return totalSpaceUsed;
    }

    public void setTotalSpaceUsed(String totalSpaceUsed) {
        this.totalSpaceUsed = totalSpaceUsed;
    }

    public String getAverageFileSize() {
        return averageFileSize;
    }

    public void setAverageFileSize(String averageFileSize) {
        this.averageFileSize = averageFileSize;
    }

    public long getTotalFileCount() {
        return totalFileCount;
    }

    public void setTotalFileCount(long totalFileCount) {
        this.totalFileCount = totalFileCount;
    }

    public long getTotalPastes() {
        return totalPastes;
    }

    public void setTotalPastes(long totalPastes) {
        this.totalPastes = totalPastes;
    }

    public long getTotalPasteViews() {
        return totalPasteViews;
    }

    public void setTotalPasteViews(long totalPasteViews) {
        this.totalPasteViews = totalPasteViews;
    }

    public String getAveragePasteLength() {
        return averagePasteLength;
    }

    public void setAveragePasteLength(String averagePasteLength) {
        this.averagePasteLength = averagePasteLength;
    }

    public long getMarkdownPasteCount() {
        return markdownPasteCount;
    }

    public void setMarkdownPasteCount(long markdownPasteCount) {
        this.markdownPasteCount = markdownPasteCount;
    }

    public long getPlainTextPasteCount() {
        return plainTextPasteCount;
    }

    public void setPlainTextPasteCount(long plainTextPasteCount) {
        this.plainTextPasteCount = plainTextPasteCount;
    }
}
