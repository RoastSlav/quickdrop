package org.rostislav.quickdrop.model;

public class AnalyticsDataView {
    private long totalDownloads;
    private String totalSpaceUsed;
    private String averageFileSize;
    private long totalFileCount;
    private long totalPastes;
    private long totalPasteViews;
    private String averagePasteLength;
    private long markdownPasteCount;
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
