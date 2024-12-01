package org.rostislav.quickdrop.service;


import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.repository.DownloadLogRepository;
import org.springframework.stereotype.Service;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

@Service
public class AnalyticsService {
    private final FileService fileService;
    private final DownloadLogRepository downloadLogRepository;

    public AnalyticsService(FileService fileService, DownloadLogRepository downloadLogRepository) {
        this.fileService = fileService;
        this.downloadLogRepository = downloadLogRepository;
    }

    public AnalyticsDataView getAnalytics() {
        long totalDownloads = downloadLogRepository.countAllDownloads();
        long totalSpaceUsed = fileService.calculateTotalSpaceUsed();

        AnalyticsDataView analytics = new AnalyticsDataView();
        analytics.setTotalDownloads(totalDownloads);
        analytics.setTotalSpaceUsed(formatFileSize(totalSpaceUsed));
        return analytics;
    }

    public long getTotalDownloads() {
        return downloadLogRepository.countAllDownloads();
    }

    public long getTotalDownloadsByFile(long id) {
        return downloadLogRepository.countDownloadsByFileId(id);
    }
}