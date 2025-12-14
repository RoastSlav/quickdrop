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

@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private final FileRepository fileRepository;
    private final FileService fileService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private final FileHistoryLogRepository fileHistoryLogRepository;
    private final ShareTokenRepository shareTokenRepository;
    private ScheduledFuture<?> scheduledTask;
    private volatile String currentCron;

    public ScheduleService(FileRepository fileRepository, FileService fileService, FileHistoryLogRepository fileHistoryLogRepository, ShareTokenRepository shareTokenRepository) {
        this.fileRepository = fileRepository;
        this.fileService = fileService;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
        this.fileHistoryLogRepository = fileHistoryLogRepository;
        this.shareTokenRepository = shareTokenRepository;
    }

    @Transactional
    public void updateSchedule(String cronExpression, long maxFileLifeTime) {
        if (cronExpression != null && cronExpression.equals(currentCron) && scheduledTask != null && !scheduledTask.isCancelled()) {
            logger.debug("Cron unchanged ({}), skipping reschedule", cronExpression);
            return;
        }

        if (scheduledTask != null && cronExpression != null) {
            scheduledTask.cancel(false);
            scheduledTask = taskScheduler.schedule(
                    () -> deleteOldFiles(maxFileLifeTime),
                    new CronTrigger(cronExpression)
            );
        }

        currentCron = cronExpression;
        logger.info("Scheduled cleanup with cron: {}", cronExpression);
    }

    @Transactional
    public void deleteOldFiles(long maxFileLifeTime) {
        logger.info("Deleting old files");
        LocalDate thresholdDate = LocalDate.now().minusDays(maxFileLifeTime);
        List<FileEntity> filesForDeletion = fileRepository.getFilesForDeletion(thresholdDate);

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
        }
    }

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
