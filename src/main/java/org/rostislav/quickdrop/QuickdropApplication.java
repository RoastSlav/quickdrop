package org.rostislav.quickdrop;

import jakarta.annotation.PostConstruct;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.PendingRestoreApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring Boot entry point for QuickDrop.
 *
 * <p>Enables Spring's task scheduling (used by {@link org.rostislav.quickdrop.service.ScheduleService})
 * and ensures the required directories exist before the application starts accepting traffic:
 * <ul>
 *   <li>{@code ./db/} — SQLite database directory, created in {@link #main} before the
 *       Spring context starts so the datasource URL is valid.</li>
 *   <li>The configured file storage path — created via {@link #createFileSavePath()} after
 *       the context is ready so the settings bean is available.</li>
 * </ul>
 *
 * <p>Also applies any database restore staged by {@link org.rostislav.quickdrop.service.BackupService}
 * before the Spring context (and its connection pool) exists — see {@link PendingRestoreApplier}.
 */
@SpringBootApplication
@EnableScheduling
public class QuickdropApplication {
    private static final Logger logger = LoggerFactory.getLogger(QuickdropApplication.class);

    private final ApplicationSettingsService applicationSettingsService;

    public QuickdropApplication(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    public static void main(String[] args) {
        try {
            Files.createDirectories(Path.of("./db"));
            Files.createDirectories(Path.of("./db-backups"));
        } catch (Exception e) {
            logger.error("Error creating directory for database", e);
        }
        try {
            PendingRestoreApplier.applyIfPending(Path.of("db", "quickdrop.db"));
        } catch (Exception e) {
            logger.error("Failed to apply a staged database restore; starting with the previous database instead. It remains staged and will be retried on the next restart.", e);
        }
        SpringApplication.run(QuickdropApplication.class, args);
    }

    @PostConstruct
    public void createFileSavePath() {
        try {
            Files.createDirectories(Path.of(applicationSettingsService.getFileStoragePath()));
            logger.info("File save path created: {}", applicationSettingsService.getFileStoragePath());
        } catch (
                Exception e) {
            logger.error("Failed to create file save path: {}", applicationSettingsService.getFileStoragePath());
        }
    }
}
