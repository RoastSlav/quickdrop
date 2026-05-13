package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.rostislav.quickdrop.repository.FileHistoryLogRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

/**
 * Computes aggregated analytics metrics for the admin dashboard.
 *
 * <p>The main {@link #getAnalytics()} result is cached under the {@code analytics}
 * cache and evicted whenever a file operation changes the underlying counts (uploads,
 * downloads, deletions, paste events). Per-file and per-paste counts are not cached
 * as they are only queried from individual detail pages.
 */
@Service
public class AnalyticsService {
    private final FileService fileService;
    private final FileHistoryLogRepository fileHistoryLogRepository;

    public AnalyticsService(FileService fileService, FileHistoryLogRepository fileHistoryLogRepository) {
        this.fileService = fileService;
        this.fileHistoryLogRepository = fileHistoryLogRepository;
    }

    /**
     * Builds and caches a complete analytics snapshot.
     *
     * <p>Aggregates total downloads, storage usage, file/paste counts, and paste
     * statistics in a single service call.
     *
     * @return cached analytics view-model
     */
    @Cacheable("analytics")
    public AnalyticsDataView getAnalytics() {
        long totalDownloads = fileHistoryLogRepository.countByEventType(FileHistoryType.DOWNLOAD);
        long totalSpaceUsed = fileService.calculateTotalSpaceUsed();
        long fileCount = fileService.getFileCount();

        String averageFileSize = "0 B";
        if (fileCount > 0) {
            averageFileSize = formatFileSize(totalSpaceUsed / fileCount);
        }

        long totalPastes = fileService.getPasteCount();
        long totalPasteViews = fileHistoryLogRepository.countByEventType(FileHistoryType.PASTE_VIEW);
        double avgPasteLengthBytes = fileService.getAveragePasteLength();
        String averagePasteLength = avgPasteLengthBytes > 0 ? Math.round(avgPasteLengthBytes) + " B" : "0 B";
        long markdownPasteCount = fileService.getMarkdownPasteCount();
        long plainTextPasteCount = Math.max(0, totalPastes - markdownPasteCount);

        AnalyticsDataView analytics = new AnalyticsDataView();
        analytics.setTotalDownloads(totalDownloads);
        analytics.setTotalSpaceUsed(formatFileSize(totalSpaceUsed));
        analytics.setAverageFileSize(averageFileSize);
        analytics.setTotalFileCount(fileCount);
        analytics.setTotalPastes(totalPastes);
        analytics.setTotalPasteViews(totalPasteViews);
        analytics.setAveragePasteLength(averagePasteLength);
        analytics.setMarkdownPasteCount(markdownPasteCount);
        analytics.setPlainTextPasteCount(plainTextPasteCount);
        return analytics;
    }

    /**
     * Returns the total number of download events for a specific file.
     *
     * @param uuid UUID of the file
     * @return download count
     */
    public long getTotalDownloadsByFile(String uuid) {
        return fileHistoryLogRepository.countByFileAndType(uuid, FileHistoryType.DOWNLOAD);
    }

    /**
     * Returns the total number of view events for a specific paste.
     *
     * @param uuid UUID of the paste
     * @return view count
     */
    public long getTotalViewsByPaste(String uuid) {
        return fileHistoryLogRepository.countByFileAndType(uuid, FileHistoryType.PASTE_VIEW);
    }

    /**
     * Returns all history log entries for a given file UUID, ordered most-recent first.
     *
     * @param fileUUID UUID of the file
     * @return ordered list of history entries
     */
    public List<FileHistoryLog> getHistoryByFile(String fileUUID) {
        return fileHistoryLogRepository.findByFileUuidOrderByEventDateDesc(fileUUID);
    }
}
