package org.rostislav.quickdrop.service;

import jakarta.annotation.PreDestroy;
import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.model.EventCategory;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.rostislav.quickdrop.util.ActivityLogCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;

/**
 * Archives and then deletes activity-log rows older than each category's configured retention.
 *
 * <p>Rows are deleted only after the CSV archive has been committed to storage, so a backend
 * outage postpones the purge instead of losing the rows. Archives are never pruned — unlike
 * database backups, each one holds events no other file has.
 */
@Service
public class ActivityRetentionService {
    private static final Logger logger = LoggerFactory.getLogger(ActivityRetentionService.class);

    /** Storage key prefix for archive objects. */
    static final String ARCHIVE_KEY_PREFIX = "activity-archive/";

    private static final int BATCH_SIZE = 1000;

    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withLocale(Locale.ROOT);

    private final ApplicationSettingsService applicationSettingsService;
    private final ActivityLogRepository activityLogRepository;
    private final StorageService storageService;
    private final AnalyticsService analyticsService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile String currentCron;
    private volatile boolean currentlyEnabled;

    public ActivityRetentionService(ApplicationSettingsService applicationSettingsService,
                                    ActivityLogRepository activityLogRepository,
                                    StorageService storageService,
                                    @Lazy AnalyticsService analyticsService) {
        this.applicationSettingsService = applicationSettingsService;
        this.activityLogRepository = activityLogRepository;
        this.storageService = storageService;
        this.analyticsService = analyticsService;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        updateSchedule(applicationSettingsService.getActivityRetentionCron(),
                applicationSettingsService.isActivityRetentionEnabled());
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        updateSchedule(event.settings().getActivityRetentionCron(),
                event.settings().isActivityRetentionEnabled());
    }

    /**
     * Replaces the scheduled sweep with a new cron expression, or cancels it when
     * {@code enabled} is {@code false}. No-ops if already in the requested state.
     */
    public synchronized void updateSchedule(String cronExpression, boolean enabled) {
        if (!enabled) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
                logger.info("Activity log retention disabled; sweep unscheduled");
            }
            currentCron = null;
            currentlyEnabled = false;
            return;
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            logger.warn("No activity retention cron expression provided; scheduling skipped");
            return;
        }
        if (currentlyEnabled && cronExpression.equals(currentCron)
                && scheduledTask != null && !scheduledTask.isCancelled()) {
            return;
        }
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = taskScheduler.schedule(this::runSweep, new CronTrigger(cronExpression));
        currentCron = cronExpression;
        currentlyEnabled = true;
        logger.info("Scheduled activity log retention sweep with cron: {}", cronExpression);
    }

    /** Sweeps every category once; one category failing doesn't stop the rest. */
    void runSweep() {
        if (!applicationSettingsService.isActivityRetentionEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int archived = 0;
        for (EventCategory category : EventCategory.values()) {
            try {
                archived += sweepCategory(category, now);
            } catch (Exception e) {
                logger.warn("Activity retention sweep failed for {}: {}", category, e.getMessage());
                logEvent(EventType.ACTIVITY_RETENTION_FAILED, category + ": " + e.getMessage());
            }
        }
        if (archived > 0) {
            logger.info("Activity retention sweep archived and deleted {} entries", archived);
        }
    }

    /**
     * Archives and deletes one category's expired rows.
     *
     * @return how many rows were archived and deleted
     * @throws Exception if the archive could not be written, leaving the rows for the next run
     */
    private int sweepCategory(EventCategory category, LocalDateTime now) throws Exception {
        int retentionDays = applicationSettingsService.getActivityRetentionDays(category);
        if (retentionDays <= 0) {
            return 0;
        }
        List<EventType> types = typesOf(category);
        LocalDateTime cutoff = now.minusDays(retentionDays);
        if (activityLogRepository.countExpiring(types, cutoff) == 0) {
            return 0;
        }

        String key = ARCHIVE_KEY_PREFIX + category.name().toLowerCase(Locale.ROOT)
                + "-" + now.format(KEY_TIMESTAMP) + ".csv";
        List<Long> archivedIds = new ArrayList<>();

        // Committed on clean close, so anything thrown here skips the delete below.
        try (OutputStream out = storageService.getOutputStream(key);
             Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            ActivityLogCsv.writeHeader(writer);
            // Offset paging is safe: nothing is deleted until the archive is committed.
            int page = 0;
            List<ActivityLog> batch;
            while (!(batch = activityLogRepository.findExpiringBatch(types, cutoff,
                    PageRequest.of(page, BATCH_SIZE))).isEmpty()) {
                for (ActivityLog log : batch) {
                    ActivityLogCsv.writeRow(writer, log);
                    archivedIds.add(log.getId());
                }
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
                page++;
            }
        }

        deleteArchived(archivedIds);
        logEvent(EventType.ACTIVITY_RETENTION_PURGED,
                category + ": " + String.format(Locale.ROOT, "%,d", archivedIds.size())
                        + " entries older than " + retentionDays + " days archived to " + key);
        return archivedIds.size();
    }

    /** Batched so a large sweep doesn't build one enormous IN clause. */
    private void deleteArchived(List<Long> ids) {
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            activityLogRepository.deleteAllByIdInBatch(ids.subList(from, Math.min(from + BATCH_SIZE, ids.size())));
        }
    }

    private static List<EventType> typesOf(EventCategory category) {
        return Arrays.stream(EventType.values()).filter(t -> t.getCategory() == category).toList();
    }

    private void logEvent(EventType eventType, String detail) {
        try {
            analyticsService.logEvent(eventType, null, null, detail);
        } catch (Exception e) {
            logger.warn("Failed to record activity retention event: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        taskScheduler.shutdown();
    }
}
