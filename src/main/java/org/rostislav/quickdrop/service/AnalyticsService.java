package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.rostislav.quickdrop.repository.FileHistoryLogRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

@Service
public class AnalyticsService {
    private final FileService fileService;
    private final FileHistoryLogRepository fileHistoryLogRepository;

    public AnalyticsService(FileService fileService, FileHistoryLogRepository fileHistoryLogRepository) {
        this.fileService = fileService;
        this.fileHistoryLogRepository = fileHistoryLogRepository;
    }

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

    public long getTotalDownloadsByFile(String uuid) {
        return fileHistoryLogRepository.countByFileAndType(uuid, FileHistoryType.DOWNLOAD);
    }

    public long getTotalViewsByPaste(String uuid) {
        return fileHistoryLogRepository.countByFileAndType(uuid, FileHistoryType.PASTE_VIEW);
    }

    public List<FileHistoryLog> getHistoryByFile(String fileUUID) {
        return fileHistoryLogRepository.findByFileUuidOrderByEventDateDesc(fileUUID);
    }
}
