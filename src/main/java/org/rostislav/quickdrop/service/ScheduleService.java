package org.rostislav.quickdrop.service;

import jakarta.annotation.PreDestroy;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages scheduled tasks for file lifecycle management.
 *
 * <p>Two categories of jobs are handled:
 * <ol>
 *   <li><strong>Dynamic cleanup job</strong> — runs on a user-configurable cron expression
 *       (set in application settings) and deletes files older than the configured
 *       {@code maxFileLifeTime}. The schedule can be updated at runtime without a
 *       restart via {@link #updateSchedule(String, long)}.</li>
 *   <li><strong>Fixed maintenance jobs</strong> — run on hardcoded cron expressions:
 *     <ul>
 *       <li>03:00 daily — {@link #cleanDatabaseFromDeletedFiles()}: soft-deletes records
 *           for uploads whose physical file no longer exists on disk (e.g. manually removed).</li>
 *       <li>03:30 daily — {@link #cleanShareTokens()}: purges expired or exhausted
 *           share tokens.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private final UploadRepository uploadRepository;
    private final FileLifecycleService fileLifecycleService;
    private final FileQueryService fileQueryService;
    private final FileDownloadService fileDownloadService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private final ShareTokenRepository shareTokenRepository;
    private final ApplicationSettingsService applicationSettingsService;
    private final StorageService storageService;
    private final ScheduleTransactionHelper scheduleTransactionHelper;
    private ScheduledFuture<?> scheduledTask;

    /**
     * The cron expression currently in use for the dynamic cleanup job.
     */
    private volatile String currentCron;

    /**
     * The maxFileLifeTime (days) captured by the currently running cleanup task.
     */
    private volatile long currentMaxFileLifeTime = -1;

    public ScheduleService(UploadRepository uploadRepository,
                           FileLifecycleService fileLifecycleService,
                           FileQueryService fileQueryService,
                           FileDownloadService fileDownloadService,
                           ShareTokenRepository shareTokenRepository,
                           ApplicationSettingsService applicationSettingsService,
                           StorageService storageService,
                           ScheduleTransactionHelper scheduleTransactionHelper) {
        this.uploadRepository = uploadRepository;
        this.fileLifecycleService = fileLifecycleService;
        this.fileQueryService = fileQueryService;
        this.fileDownloadService = fileDownloadService;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
        this.shareTokenRepository = shareTokenRepository;
        this.applicationSettingsService = applicationSettingsService;
        this.storageService = storageService;
        this.scheduleTransactionHelper = scheduleTransactionHelper;
    }

    /**
     * Replaces the dynamic file-deletion schedule with a new cron expression.
     *
     * <p>If the expression and lifetime match the currently running schedule, the call
     * is a no-op. If a task is already scheduled, it is cancelled (without interrupting
     * a running execution) before the new task is registered.
     *
     * <p>Synchronised to prevent two concurrent settings saves from registering
     * duplicate tasks when both pass the equality guard at the same time.
     *
     * @param cronExpression  Spring-compatible 6-field cron expression
     * @param maxFileLifeTime maximum file age in days; files older than this are deleted
     */
    public synchronized void updateSchedule(String cronExpression, long maxFileLifeTime) {
        if (cronExpression == null || cronExpression.isBlank()) {
            logger.warn("No cron expression provided; cleanup scheduling skipped");
            return;
        }

        if (cronExpression.equals(currentCron) && maxFileLifeTime == currentMaxFileLifeTime
                && scheduledTask != null && !scheduledTask.isCancelled()) {
            logger.debug("Schedule unchanged (cron={}, maxLife={}d), skipping reschedule", cronExpression, maxFileLifeTime);
            return;
        }

        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }

        scheduledTask = taskScheduler.schedule(
                () -> deleteOldFiles(maxFileLifeTime),
                new CronTrigger(cronExpression)
        );

        currentCron = cronExpression;
        currentMaxFileLifeTime = maxFileLifeTime;
        logger.info("Scheduled cleanup with cron: {} and max life: {} days", cronExpression, maxFileLifeTime);
    }

    /**
     * Shuts down the dynamic-cleanup scheduler gracefully on application stop.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down file cleanup scheduler");
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        taskScheduler.shutdown();
    }

    /**
     * Deletes uploads (files and pastes) that have exceeded the configured maximum lifetime.
     *
     * <p>Only uploads with {@code keepIndefinitely = false} and an {@code uploadDate}
     * strictly before {@code today - maxFileLifeTime} are eligible. Uploads whose
     * physical file cannot be removed from the filesystem are not soft-deleted.
     *
     * @param maxFileLifeTime maximum upload age in days
     */
    public void deleteOldFiles(long maxFileLifeTime) {
        logger.info("Deleting old files (max life: {} days)", maxFileLifeTime);
        LocalDate thresholdDate = LocalDate.now().minusDays(maxFileLifeTime);
        List<Upload> filesForDeletion = uploadRepository.getUploadsForDeletion(thresholdDate);

        if (filesForDeletion.isEmpty()) {
            logger.info("No files eligible for deletion (threshold date: {})", thresholdDate);
            return;
        }

        List<Long> deletedIds = new ArrayList<>();
        for (Upload file : filesForDeletion) {
            logger.info("Attempting filesystem delete for file: {}", file);
            boolean deleted = fileLifecycleService.deleteFileFromFileSystem(file.uuid);
            if (deleted) {
                deletedIds.add(file.id);
            } else {
                logger.error("Failed to delete file from filesystem: {}", file);
            }
        }

        if (!deletedIds.isEmpty()) {
            // Delegate DB work to the helper so the operation runs inside a real transaction.
            scheduleTransactionHelper.deleteFilesAndHistory(deletedIds);
            logger.info("Deleted {} files (threshold date: {})", deletedIds.size(), thresholdDate);
        } else {
            logger.warn("No database soft-deletions performed; all filesystem deletions failed or nothing matched");
        }
    }

    /**
     * Soft-deletes records for uploads whose physical file no longer exists on disk
     * (e.g. the file was removed manually from the filesystem), then removes any
     * {@code {uuid}-decrypted} sidecar files whose share tokens have all expired or
     * been exhausted.
     *
     * <p>Only non-deleted records are scanned; uploads that were already soft-deleted
     * legitimately have no file on disk and are skipped.
     *
     * <p>Runs daily at 03:00.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanDatabaseFromDeletedFiles() {
        logger.info("Cleaning database from deleted files");

        List<String> uuidsToRemove = new ArrayList<>();
        int page = 0;
        final int BATCH_SIZE = 100;
        Page<Upload> batch;
        do {
            // Only scan non-deleted files; soft-deleted files legitimately have no file on disk.
            batch = uploadRepository.findAllNotDeleted(PageRequest.of(page++, BATCH_SIZE));
            for (Upload file : batch) {
                if (!fileQueryService.fileExistsInFileSystem(file.uuid)) {
                    uuidsToRemove.add(file.uuid);
                }
            }
        } while (batch.hasNext());

        // Remove legacy plaintext {uuid}-decrypted sidecars that have no active share tokens
        storageService.listKeySuffix("-decrypted").forEach(key -> {
            String uuid = key.replace("-decrypted", "");
            uploadRepository.findByUUID(uuid).ifPresentOrElse(
                    file -> {
                        if (!shareTokenRepository.existsValidTokenForFile(file, LocalDate.now())) {
                            storageService.delete(key);
                            logger.info("Deleted legacy decrypted sidecar: {}", key);
                        }
                    },
                    () -> {
                        storageService.delete(key);
                        logger.info("Deleted orphaned legacy sidecar: {}", key);
                    }
            );
        });

        if (!uuidsToRemove.isEmpty()) {
            // Delegate to the helper so each removeFileFromDatabase call is wrapped in a transaction.
            scheduleTransactionHelper.softDeleteByUuids(uuidsToRemove);
            logger.info("Removed {} database records for missing files", uuidsToRemove.size());
        }
    }

    /**
     * Deletes share tokens that have either passed their expiry date or exhausted
     * their download allowance, and removes their associated re-encrypted sidecars.
     * Runs daily at 03:30.
     *
     * <p>DB rows are deleted first (inside a transaction via the helper) so that a
     * crash after the DB delete but before any sidecar cleanup leaves orphaned sidecars
     * rather than dangling token records — orphaned sidecars are cleaned up by the
     * daily {@link #cleanDatabaseFromDeletedFiles()} scan.
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanShareTokens() {
        logger.info("Cleaning invalid share tokens");
        List<ShareTokenEntity> toDelete = shareTokenRepository.getShareTokenEntitiesForDeletion(LocalDate.now());
        if (!toDelete.isEmpty()) {
            toDelete.forEach(token -> fileDownloadService.deleteShareSidecar(token));
            // Delete DB rows inside a transaction, then handle any sidecar cleanup.
            scheduleTransactionHelper.deleteExpiredShareTokens(toDelete);
            logger.info("Deleted {} invalid share tokens", toDelete.size());
        } else {
            logger.debug("No invalid share tokens found");
        }
    }
}
