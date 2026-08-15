package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static org.rostislav.quickdrop.model.EventType.DOWNLOAD;
import static org.rostislav.quickdrop.model.EventType.SHARE_DOWNLOAD;

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
    private final FileQueryService fileQueryService;
    private final PasteService pasteService;
    private final ActivityLogRepository activityLogRepository;

    public AnalyticsService(FileQueryService fileQueryService, PasteService pasteService, ActivityLogRepository activityLogRepository) {
        this.fileQueryService = fileQueryService;
        this.pasteService = pasteService;
        this.activityLogRepository = activityLogRepository;
    }

    /**
     * Builds and caches a complete analytics snapshot.
     *
     * <p>Aggregates total downloads (counting both direct {@code DOWNLOAD} and
     * {@code SHARE_DOWNLOAD} events), storage usage, file/paste counts, and paste
     * statistics in a single service call.
     *
     * @return cached analytics view-model
     */
    /**
     * Most recent activity-log entries, newest first.
     *
     * <p>Deliberately not cached: the dashboard feed is meant to reflect what just
     * happened, and the {@code analytics} cache is only evicted on file mutations.
     *
     * @param limit maximum number of entries to return
     * @return recent activity entries
     */
    public List<ActivityLog> getRecentActivity(int limit) {
        return activityLogRepository
                .findFiltered(null, null, null, null, null, null, PageRequest.of(0, limit))
                .getContent();
    }

    @Cacheable("analytics")
    public AnalyticsDataView getAnalytics() {
        long totalDownloads = activityLogRepository.countByEventTypeIn(List.of(DOWNLOAD, SHARE_DOWNLOAD));
        long totalSpaceUsed = fileQueryService.calculateTotalSpaceUsed();
        long fileCount = fileQueryService.getFileCount();

        String averageFileSize = "0 B";
        if (fileCount > 0) {
            averageFileSize = formatFileSize(totalSpaceUsed / fileCount);
        }

        long totalPastes = pasteService.getPasteCount();
        long totalPasteViews = activityLogRepository.countByEventType(EventType.PASTE_VIEW);
        double avgPasteLengthBytes = pasteService.getAveragePasteLength();
        String averagePasteLength = avgPasteLengthBytes > 0 ? Math.round(avgPasteLengthBytes) + " B" : "0 B";
        long markdownPasteCount = pasteService.getMarkdownPasteCount();
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
     * Returns the total number of download events for a specific file, counting both
     * direct downloads ({@code DOWNLOAD}) and share-link downloads ({@code SHARE_DOWNLOAD}).
     *
     * @param uuid UUID of the file
     * @return combined download count
     */
    public long getTotalDownloadsByFile(String uuid) {
        return activityLogRepository.countByFileAndTypeIn(uuid, List.of(DOWNLOAD, SHARE_DOWNLOAD));
    }

    /**
     * Returns the total number of view events for a specific paste.
     *
     * @param uuid UUID of the paste
     * @return view count
     */
    public long getTotalViewsByPaste(String uuid) {
        return activityLogRepository.countByFileAndType(uuid, EventType.PASTE_VIEW);
    }

    /**
     * Returns all history log entries for a given file UUID, ordered most-recent first.
     *
     * @param fileUUID UUID of the file
     * @return ordered list of history entries
     */
    public List<ActivityLog> getHistoryByFile(String fileUUID) {
        return activityLogRepository.findByFileUuidOrderByEventDateDesc(fileUUID);
    }

    /**
     * Records an audit event that is not associated with any specific file.
     * Covers both {@link org.rostislav.quickdrop.model.EventCategory#ADMIN}
     * events (login, logout, settings change) and
     * {@link org.rostislav.quickdrop.model.EventCategory#SYSTEM} events
     * (application startup / shutdown).
     *
     * @param eventType the event type to record
     * @param ip        requester IP address, or {@code null} for system events
     * @param ua        requester User-Agent header value, or {@code null} for system events
     */
    @CacheEvict(value = "analytics", allEntries = true)
    public void logEvent(EventType eventType, String ip, String ua) {
        activityLogRepository.save(new ActivityLog(eventType, ip, ua));
    }

    /**
     * Returns a filtered, paginated slice of the global activity log.
     * Any parameter that is {@code null} is treated as "no filter on this dimension".
     *
     * @param startDate  lower bound on event timestamp (inclusive), or {@code null}
     * @param endDate    upper bound on event timestamp (inclusive), or {@code null}
     * @param eventType  exact event type filter, or {@code null} to include all types
     * @param ip         substring filter on IP address, or {@code null}
     * @param ua         substring filter on user-agent, or {@code null}
     * @param sourceType one of {@code "file"}, {@code "paste"}, {@code "system"}, or {@code null} for all
     * @param pageable   pagination and sort configuration
     * @return a page of matching log entries ordered by event date descending
     */
    public Page<ActivityLog> getFilteredActivity(LocalDateTime startDate, LocalDateTime endDate,
                                                 EventType eventType, String ip, String ua, String sourceType,
                                                 Pageable pageable) {
        String sourceTypeFilter = (sourceType == null || sourceType.isBlank()) ? null : sourceType.toLowerCase();
        return activityLogRepository.findFiltered(startDate, endDate, eventType, ip, ua, sourceTypeFilter, pageable);
    }
}
