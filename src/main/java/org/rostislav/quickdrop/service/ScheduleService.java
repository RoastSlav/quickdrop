package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private final FileRepository fileRepository;
    private final FileService fileService;
    @Value("${file.max.age}")
    private int maxFileAge;

    public ScheduleService(FileRepository fileRepository, FileService fileService) {
        this.fileRepository = fileRepository;
        this.fileService = fileService;
    }

    @Scheduled(cron = "${file.deletion.cron}")
    public void deleteOldFiles() {
        logger.info("Deleting old files");
        LocalDate thresholdDate = LocalDate.now().minusDays(maxFileAge);
        List<FileEntity> filesForDeletion = fileRepository.getFilesForDeletion(thresholdDate);
        for (FileEntity file : filesForDeletion) {
            logger.info("Deleting file: {}", file);
            boolean deleted = fileService.deleteFileFromFileSystem(file.uuid);
            if (deleted) {
                fileRepository.delete(file);
            } else {
                logger.error("Failed to delete file: {}", file);
            }
        }
        logger.info("Deleted {} files", filesForDeletion.size());
    }
}
