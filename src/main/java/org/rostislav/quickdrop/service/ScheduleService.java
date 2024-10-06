package org.rostislav.quickdrop.service;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private final FileRepository fileRepository;
    @Value("${file.max.age}")
    private int maxFileAge;

    public ScheduleService(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    @Transactional
    @Scheduled(cron = "0 0 2 * * *")
    public void deleteOldFiles() {
        logger.info("Deleting old files");
        LocalDate thresholdDate = LocalDate.now().minusDays(maxFileAge);
        int deletedFilesCount = fileRepository.deleteOldFiles(thresholdDate);
        logger.info("Deleted {} files", deletedFilesCount);
    }
}
