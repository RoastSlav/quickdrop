package org.rostislav.quickdrop.service;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.repository.FileHistoryLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *       <li>02:00 daily — {@link #cleanDatabaseFromDeletedFiles()}: removes database
 *           rows for files that no longer exist on disk (e.g. manually deleted).</li>
 *       <li>03:30 daily — {@link #cleanShareTokens()}: purges expired or exhausted
 *           share tokens.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private final FileRepository fileRepository;
    private final FileService fileService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private final FileHistoryLogRepository fileHistoryLogRepository;
    private final ShareTokenRepository shareTokenRepository;
    private ScheduledFuture<?> scheduledTask;

    /**
     * The cron expression currently in use for the dynamic cleanup job.
     */
    private volatile String currentCron;

    public ScheduleService(FileRepository fileRepository, FileService fileService, FileHistoryLogRepository fileHistoryLogRepository, ShareTokenRepository shareTokenRepository) {
        this.fileRepository = fileRepository;
        this.fileService = fileService;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
        this.fileHistoryLogRepository = fileHistoryLogRepository;
        this.shareTokenRepository = shareTokenRepository;
    }

    /**
     * Replaces the dynamic file-deletion schedule with a new cron expression.
     *
     * <p>If the expression matches the currently running schedule, the call is a
     * no-op. If a task is already scheduled, it is cancelled (without interrupting
     * a running execution) before the new task is registered.
     *
     * @param cronExpression Spring-compatible 6-field cron expression
     * @param maxFileLifeTime maximum file age in days; files older than this are deleted
     */
    @Transactional
    public void updateSchedule(String cronExpression, long maxFileLifeTime) {
        if (cronExpression == null || cronExpression.isBlank()) {
            logger.warn("No cron expression provided; cleanup scheduling skipped");
            return;
        }

        if (cronExpression.equals(currentCron) && scheduledTask != null && !scheduledTask.isCancelled()) {
            logger.debug("Cron unchanged ({}), skipping reschedule", cronExpression);
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
        logger.info("Scheduled cleanup with cron: {} and max life: {} days", cronExpression, maxFileLifeTime);
    }

    /**
     * Deletes files that have exceeded the configured maximum lifetime.
     *
     * <p>Only files with {@code keepIndefinitely = false} and an {@code uploadDate}
     * strictly before {@code today - maxFileLifeTime} are eligible. Files are removed
     * from the filesystem first; only those successfully removed are then deleted from
     * the database (along with their history logs).
     *
     * @param maxFileLifeTime maximum file age in days
     */
    @Transactional
    public void deleteOldFiles(long maxFileLifeTime) {
        logger.info("Deleting old files (max life: {} days)", maxFileLifeTime);
        LocalDate thresholdDate = LocalDate.now().minusDays(maxFileLifeTime);
        List<FileEntity> filesForDeletion = fileRepository.getFilesForDeletion(thresholdDate);

        if (filesForDeletion.isEmpty()) {
            logger.info("No files eligible for deletion (threshold date: {})", thresholdDate);
            return;
        }

        List<Long> deletedIds = new ArrayList<>();
        for (FileEntity file : filesForDeletion) {
            logger.info("Attempting filesystem delete for file: {}", file);
            boolean deleted = fileService.deleteFileFromFileSystem(file.uuid);
            if (deleted) {
                deletedIds.add(file.id);
            } else {
                logger.error("Failed to delete file from filesystem: {}", file);
            }
        }

        if (!deletedIds.isEmpty()) {
            deletedIds.forEach(fileHistoryLogRepository::deleteByFileId);
            fileRepository.deleteAllById(deletedIds);
            logger.info("Deleted {} files (threshold date: {})", deletedIds.size(), thresholdDate);
        } else {
            logger.warn("No database deletions performed; all filesystem deletions failed or nothing matched");
        }
    }

    /**
     * Removes database rows for files whose physical file no longer exists on disk.
     * Runs daily at 03:00 to catch files deleted outside the application.
     */
    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanDatabaseFromDeletedFiles() {
        logger.info("Cleaning database from deleted files");

        fileRepository.findAll().forEach(file -> {
            if (!fileService.fileExistsInFileSystem(file.uuid)) {
                fileService.removeFileFromDatabase(file.uuid);
            }
        });
    }

    /**
     * Deletes share tokens that have either passed their expiry date or exhausted
     * their download allowance. Runs daily at 03:30.
     */
    @Transactional
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanShareTokens() {
        logger.info("Cleaning invalid share tokens");
        List<ShareTokenEntity> toDelete = shareTokenRepository.getShareTokenEntitiesForDeletion();
        if (!toDelete.isEmpty()) {
            shareTokenRepository.deleteAll(toDelete);
            logger.info("Deleted {} invalid share tokens", toDelete.size());
        } else {
            logger.debug("No invalid share tokens found");
        }
    }
}
