package org.rostislav.quickdrop.service;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.repository.DownloadLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private final FileRepository fileRepository;
    private final FileService fileService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private final DownloadLogRepository downloadLogRepository;
    private ScheduledFuture<?> scheduledTask;

    public ScheduleService(FileRepository fileRepository, FileService fileService, DownloadLogRepository downloadLogRepository) {
        this.fileRepository = fileRepository;
        this.fileService = fileService;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
        this.downloadLogRepository = downloadLogRepository;
    }

    public void updateSchedule(String cronExpression, long maxFileLifeTime) {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }

        scheduledTask = taskScheduler.schedule(
                () -> deleteOldFiles(maxFileLifeTime),
                new CronTrigger(cronExpression)
        );
    }

    @Transactional
    public void deleteOldFiles(long maxFileLifeTime) {
        logger.info("Deleting old files");
        LocalDate thresholdDate = LocalDate.now().minusDays(maxFileLifeTime);
        List<FileEntity> filesForDeletion = fileRepository.getFilesForDeletion(thresholdDate);
        for (FileEntity file : filesForDeletion) {
            logger.info("Deleting file: {}", file);
            boolean deleted = fileService.deleteFileFromFileSystem(file.uuid);
            if (deleted) {
                fileRepository.delete(file);
                downloadLogRepository.deleteByFileId(file.id);
            } else {
                logger.error("Failed to delete file: {}", file);
            }
        }
        logger.info("Deleted {} files", filesForDeletion.size());
    }

    @Transactional
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanDatabaseFromDeletedFiles() {
        logger.info("Cleaning database from deleted files");
        fileRepository.findAll().forEach(file -> {
            if (!fileService.fileExistsInFileSystem(file.uuid)) {
                fileRepository.delete(file);
                downloadLogRepository.deleteByFileId(file.id);
            }
        });
    }
}
