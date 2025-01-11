package org.rostislav.quickdrop.service;


import org.rostislav.quickdrop.entity.DownloadLog;
import org.rostislav.quickdrop.entity.FileRenewalLog;
import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.repository.DownloadLogRepository;
import org.rostislav.quickdrop.repository.RenewalLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

@Service
public class AnalyticsService {
    private final FileService fileService;
    private final DownloadLogRepository downloadLogRepository;
    private final RenewalLogRepository renewalLogRepository;

    public AnalyticsService(FileService fileService, DownloadLogRepository downloadLogRepository, RenewalLogRepository renewalLogRepository) {
        this.fileService = fileService;
        this.downloadLogRepository = downloadLogRepository;
        this.renewalLogRepository = renewalLogRepository;
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

    public long getTotalDownloadsByFile(String uuid) {
        return downloadLogRepository.countDownloadsByFileId(uuid);
    }

    public List<DownloadLog> getDownloadsByFile(String fileUUID) {
        return downloadLogRepository.findByFileUuid(fileUUID);
    }

    public List<FileRenewalLog> getRenewalLogsByFile(String fileUUID) {
        return renewalLogRepository.findByFileUuid(fileUUID);
    }
}