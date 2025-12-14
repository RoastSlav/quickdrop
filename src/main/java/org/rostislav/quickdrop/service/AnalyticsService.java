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

        AnalyticsDataView analytics = new AnalyticsDataView();
        analytics.setTotalDownloads(totalDownloads);
        analytics.setTotalSpaceUsed(formatFileSize(totalSpaceUsed));
        analytics.setAverageFileSize(averageFileSize);
        return analytics;
    }

    public long getTotalDownloadsByFile(String uuid) {
        return fileHistoryLogRepository.countByFileAndType(uuid, FileHistoryType.DOWNLOAD);
    }

    public List<FileHistoryLog> getHistoryByFile(String fileUUID) {
        return fileHistoryLogRepository.findByFileUuidOrderByEventDateDesc(fileUUID);
    }
}