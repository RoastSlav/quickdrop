package org.rostislav.quickdrop.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Overrides the real {@link BackupService}/{@link RestartTrigger} beans for controller-slice
 * tests, so they don't touch the repo's real {@code db-backups/} directory or exit the test JVM
 * on a successful restore. Lives in this package (not nested in the controller test) so it can
 * reach {@link BackupService}'s package-private, {@code @TempDir}-friendly constructor.
 */
@TestConfiguration
public class TestBackupServiceConfig {

    @Bean
    @Primary
    public BackupService testBackupService(ApplicationSettingsService applicationSettingsService,
                                            AnalyticsService analyticsService, DataSource dataSource) {
        try {
            Path tempDir = Files.createTempDirectory("quickdrop-backup-controller-test-");
            return new BackupService(applicationSettingsService, analyticsService, dataSource,
                    tempDir.resolve("quickdrop.db"), tempDir.resolve("db-backups"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    @Primary
    public RestartTrigger testRestartTrigger() {
        return new RecordingRestartTrigger();
    }

    /** Records calls instead of exiting the JVM, so a restore-success test can assert a restart was actually requested. */
    public static class RecordingRestartTrigger implements RestartTrigger {
        public final AtomicInteger callCount = new AtomicInteger();

        @Override
        public void scheduleRestart(long delayMillis) {
            callCount.incrementAndGet();
        }
    }
}
