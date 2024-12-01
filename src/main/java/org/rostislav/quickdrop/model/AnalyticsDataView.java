package org.rostislav.quickdrop.model;

public class AnalyticsDataView {
    private long totalDownloads;
    private String totalSpaceUsed;

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
}