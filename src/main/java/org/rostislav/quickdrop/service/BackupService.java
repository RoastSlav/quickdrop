package org.rostislav.quickdrop.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;

/**
 * Creates, lists, restores and prunes backups of the SQLite database.
 *
 * <p>Backups are plain {@code .db} files produced by {@code VACUUM INTO}, not a raw copy of
 * {@code db/quickdrop.db} — in WAL mode a raw copy can miss data still in the {@code -wal}
 * sidecar, and a plain file is a genuine drop-in replacement with nothing to undo first.
 */
@Service
public class BackupService {
    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);
    // Millisecond precision avoids filename collisions between backups created in quick succession.
    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS");
    private static final String FILE_PREFIX = "quickdrop-";
    private static final String FILE_SUFFIX = ".db";
    // Mirrors ApplicationSettingsEntity's own default; used when the stored value is unusable.
    private static final int DEFAULT_MAX_BACKUPS = 7;

    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final DataSource dataSource;
    private final Path dbFile;
    private final Path backupDir;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile String currentCron;
    private volatile boolean currentScheduleEnabled;

    /** One entry in {@link #listBackups()}. */
    public record BackupInfo(String filename, long sizeBytes, Instant createdAt) {
    }

    /** Outcome of {@link #createBackup()}, {@link #restoreBackup(String)} or {@link #deleteBackup(String)}. */
    public record BackupResult(boolean success, String message) {
    }

    @Autowired
    public BackupService(ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService, DataSource dataSource) {
        this(applicationSettingsService, analyticsService, dataSource, Path.of("db", "quickdrop.db"), AppPaths.BACKUPS);
    }

    /** Lets tests redirect the db file and backup directory to a {@code @TempDir}. */
    BackupService(ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService,
                 DataSource dataSource, Path dbFile, Path backupDir) {
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.dataSource = dataSource;
        this.dbFile = dbFile;
        this.backupDir = backupDir;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
    }

    /**
     * Records where backups actually land and how many survived the last shutdown: a count of zero
     * on an instance that has been taking backups means the directory did not persist the restart.
     */
    @PostConstruct
    public void logBackupLocation() {
        logger.info("Backup directory: {} ({} backup(s) present at startup)",
                backupDir.toAbsolutePath().normalize(), listBackups().size());
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        updateSchedule(event.settings().getBackupCron(), event.settings().isBackupScheduleEnabled());
    }

    /**
     * Replaces the scheduled backup job with a new cron expression, or cancels it when
     * {@code enabled} is {@code false}. No-ops if already in the requested state.
     *
     * @param cronExpression Spring-compatible 6-field cron expression
     * @param enabled        whether the schedule should be active at all
     */
    public synchronized void updateSchedule(String cronExpression, boolean enabled) {
        if (!enabled) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
            currentCron = null;
            currentScheduleEnabled = false;
            return;
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            logger.warn("Backup schedule enabled but no cron expression provided; scheduling skipped");
            return;
        }
        if (cronExpression.equals(currentCron) && currentScheduleEnabled
                && scheduledTask != null && !scheduledTask.isCancelled()) {
            return;
        }
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = taskScheduler.schedule(this::runScheduledBackup, new CronTrigger(cronExpression));
        currentCron = cronExpression;
        currentScheduleEnabled = true;
        logger.info("Scheduled database backups with cron: {}", cronExpression);
    }

    /** No request to attribute the event to, so this path logs its own outcome as a system event. */
    private void runScheduledBackup() {
        BackupResult result = createBackup();
        if (result.success()) {
            analyticsService.logEvent(EventType.BACKUP_CREATED, null, null);
        } else {
            logger.error("Scheduled database backup failed: {}", result.message());
            analyticsService.logEvent(EventType.BACKUP_FAILED, null, null);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        taskScheduler.shutdown();
    }

    /**
     * Creates a new backup via {@code VACUUM INTO} and prunes older ones beyond {@code maxBackups}.
     * Does not itself log to the activity log — the caller decides how to attribute the event.
     *
     * @return a result carrying the new backup's filename on success, or an error message
     */
    public synchronized BackupResult createBackup() {
        try {
            Files.createDirectories(backupDir);
            String filename = FILE_PREFIX + LocalDateTime.now().format(FILENAME_TIMESTAMP) + FILE_SUFFIX;
            Path target = backupDir.resolve(filename);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escapeSqlLiteral(target.toAbsolutePath().toString()) + "'");
            }

            prune();
            logger.info("Database backup created: {}", filename);
            return new BackupResult(true, filename);
        } catch (Exception e) {
            logger.error("Database backup failed", e);
            return new BackupResult(false, e.getMessage());
        }
    }

    private static String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    /**
     * Imports an uploaded file as a new backup, validated the same way {@link #restoreBackup}
     * validates before restoring. The uploaded filename is discarded for a generated one, so
     * the import participates in listing/pruning like any backup this instance created itself.
     *
     * @param file the uploaded database file
     * @return a result carrying the new backup's filename on success, or an error message
     */
    public synchronized BackupResult uploadBackup(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new BackupResult(false, "No file uploaded");
        }
        try {
            Files.createDirectories(backupDir);
            String filename = FILE_PREFIX + LocalDateTime.now().format(FILENAME_TIMESTAMP) + FILE_SUFFIX;
            Path target = backupDir.resolve(filename);
            file.transferTo(target);

            if (!looksLikeValidSqliteFile(target)) {
                Files.deleteIfExists(target);
                return new BackupResult(false, "Uploaded file failed integrity check");
            }

            prune();
            logger.info("Database backup uploaded: {}", filename);
            return new BackupResult(true, filename);
        } catch (Exception e) {
            logger.error("Database backup upload failed", e);
            return new BackupResult(false, e.getMessage());
        }
    }

    /**
     * Deletes backups beyond the configured {@code maxBackups}, oldest first. A retention of zero
     * or less is treated as unset rather than as "keep nothing": taken literally it would delete
     * every backup including the one just written, and the UI never stores a value below 1.
     */
    private void prune() throws IOException {
        int configured = applicationSettingsService.getApplicationSettings().getMaxBackups();
        int maxBackups = configured > 0 ? configured : DEFAULT_MAX_BACKUPS;
        if (configured != maxBackups) {
            logger.warn("Configured backups-to-keep is {}; falling back to {}", configured, maxBackups);
        }
        List<Path> backups = listBackupPaths();
        // When backups go missing, this line says whether retention removed them, and under which setting.
        logger.info("Backup retention: {} backup(s) on disk, keeping the newest {}", backups.size(), maxBackups);
        for (int i = maxBackups; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
            logger.info("Pruned old backup: {}", backups.get(i).getFileName());
        }
    }

    /** Backup files newest-first; the filename's timestamp sorts identically to creation order. */
    private List<Path> listBackupPaths() throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> result = files
                    .filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX) && p.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            return new ArrayList<>(result);
        }
    }

    /**
     * Lists existing backups, newest first.
     *
     * @return backup metadata; empty if the backup directory does not exist yet
     */
    public List<BackupInfo> listBackups() {
        try {
            List<BackupInfo> infos = new ArrayList<>();
            for (Path path : listBackupPaths()) {
                infos.add(new BackupInfo(
                        path.getFileName().toString(),
                        Files.size(path),
                        parseTimestamp(path.getFileName().toString())
                ));
            }
            return infos;
        } catch (IOException e) {
            logger.error("Failed to list backups", e);
            return List.of();
        }
    }

    private static Instant parseTimestamp(String filename) {
        String stem = filename.substring(FILE_PREFIX.length(), filename.length() - FILE_SUFFIX.length());
        try {
            return LocalDateTime.parse(stem, FILENAME_TIMESTAMP).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * Resolves {@code filename} against the backup directory and rejects anything that would
     * escape it (path traversal, an absolute path, etc.).
     *
     * @return the confined path, or {@code null} if {@code filename} is unsafe
     */
    private Path resolveConfined(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        Path backupDirAbs = backupDir.toAbsolutePath().normalize();
        Path candidate = backupDirAbs.resolve(filename).normalize();
        if (!candidate.startsWith(backupDirAbs) || !candidate.getParent().equals(backupDirAbs)) {
            return null;
        }
        return candidate;
    }

    /**
     * Validates {@code filename} and stages it to replace the live database. The actual file
     * swap happens on next startup, via {@link PendingRestoreApplier} — not here, since the
     * live connection pool has {@code dbFile} and its {@code -wal}/{@code -shm} sidecars open
     * (replacing them while they're open fails outright on Windows). Does not trigger a restart
     * or log the outcome — the caller (with the request context to attribute it to) does both.
     *
     * @param filename the backup file to restore, as returned by {@link #listBackups()}
     * @return a result describing success or the specific validation failure
     */
    public synchronized BackupResult restoreBackup(String filename) {
        Path candidate = resolveConfined(filename);
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return new BackupResult(false, "Backup not found: " + filename);
        }

        if (!looksLikeValidSqliteFile(candidate)) {
            return new BackupResult(false, "Backup file failed integrity check: " + filename);
        }

        try {
            Path pending = Path.of(dbFile + PendingRestoreApplier.PENDING_SUFFIX);
            Files.copy(candidate, pending, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            logger.info("Database restore staged from backup: {}", filename);
            return new BackupResult(true, filename);
        } catch (IOException e) {
            logger.error("Database restore staging failed", e);
            return new BackupResult(false, e.getMessage());
        }
    }

    private boolean looksLikeValidSqliteFile(Path candidate) {
        String jdbcUrl = "jdbc:sqlite:" + candidate.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA quick_check")) {
            return resultSet.next() && "ok".equalsIgnoreCase(resultSet.getString(1));
        } catch (SQLException e) {
            logger.warn("Backup file failed SQLite integrity check: {}", candidate.getFileName(), e);
            return false;
        }
    }

    /**
     * Deletes one backup outside the normal pruning cycle.
     *
     * @param filename the backup file to delete, as returned by {@link #listBackups()}
     * @return a result describing success or why the file could not be deleted
     */
    public BackupResult deleteBackup(String filename) {
        Path candidate = resolveConfined(filename);
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return new BackupResult(false, "Backup not found: " + filename);
        }
        try {
            Files.delete(candidate);
            logger.info("Backup deleted: {}", filename);
            return new BackupResult(true, filename);
        } catch (IOException e) {
            logger.error("Failed to delete backup: {}", filename, e);
            return new BackupResult(false, e.getMessage());
        }
    }

    /**
     * Resolves a backup filename to its absolute path for streaming/download, applying the
     * same path-confinement check as {@link #restoreBackup(String)}.
     *
     * @param filename the backup file to resolve, as returned by {@link #listBackups()}
     * @return the confined path, or {@code null} if {@code filename} is unsafe or missing
     */
    public Path resolveForDownload(String filename) {
        Path candidate = resolveConfined(filename);
        return (candidate != null && Files.isRegularFile(candidate)) ? candidate : null;
    }
}
