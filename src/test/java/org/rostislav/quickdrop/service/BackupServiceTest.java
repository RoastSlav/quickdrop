package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link BackupService} against the test context's real (isolated, per-context) SQLite
 * {@link DataSource}, but with {@code dbFile}/{@code backupDir} redirected to a fresh
 * {@code @TempDir} via the package-private constructor -- nothing here ever touches the repo's
 * real {@code db/} or {@code db-backups/} directories.
 */
class BackupServiceTest extends QuickdropIntegrationTest {

    @TempDir
    private Path tempDir;

    @Autowired
    private ApplicationSettingsService applicationSettingsService;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private FileRepository fileRepository;

    private Path dbFile;
    private Path backupDir;
    private BackupService backupService;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("quickdrop.db");
        backupDir = tempDir.resolve("db-backups");
        backupService = new BackupService(applicationSettingsService, analyticsService, dataSource, dbFile, backupDir);
    }

    @AfterEach
    void tearDown() {
        backupService.shutdown();
        setMaxBackups(7);
    }

    private void setMaxBackups(int maxBackups) {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setMaxBackups(maxBackups);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
    }

    private StoredFile persistFile() {
        StoredFile file = new StoredFile();
        file.uuid = UUID.randomUUID().toString();
        file.name = "f-" + file.uuid + ".txt";
        file.size = 5;
        file.uploadDate = LocalDate.now();
        return fileRepository.save(file);
    }

    // -------------------------------------------------------------------------
    // createBackup
    // -------------------------------------------------------------------------

    @Test
    void createBackup_producesAValidStandaloneSqliteFileContainingCurrentData() throws Exception {
        StoredFile file = persistFile();

        BackupService.BackupResult result = backupService.createBackup();

        assertTrue(result.success(), () -> "backup should succeed: " + result.message());
        Path backupFile = backupDir.resolve(result.message());
        assertTrue(Files.isRegularFile(backupFile), "backup file must actually be written to disk");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backupFile.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT uuid FROM upload WHERE uuid = '" + file.uuid + "'")) {
            assertTrue(resultSet.next(), "a row present at backup time must be readable directly from the backup file");
        }
    }

    @Test
    void createBackup_resultIsListedByListBackups() {
        BackupService.BackupResult result = backupService.createBackup();

        List<BackupService.BackupInfo> backups = backupService.listBackups();
        assertEquals(1, backups.size());
        assertEquals(result.message(), backups.get(0).filename());
    }

    @Test
    void listBackups_isEmptyWhenBackupDirDoesNotExistYet() {
        assertTrue(backupService.listBackups().isEmpty());
    }

    // -------------------------------------------------------------------------
    // uploadBackup
    // -------------------------------------------------------------------------

    @Test
    void uploadBackup_validSqliteFile_isAcceptedAndListed() throws Exception {
        byte[] validSqliteBytes = Files.readAllBytes(backupDir.resolve(backupService.createBackup().message()));
        MockMultipartFile upload = new MockMultipartFile("file", "external.db", "application/octet-stream", validSqliteBytes);

        BackupService.BackupResult result = backupService.uploadBackup(upload);

        assertTrue(result.success(), () -> "upload should succeed: " + result.message());
        assertTrue(backupService.listBackups().stream().anyMatch(b -> b.filename().equals(result.message())),
                "an accepted upload must appear in the backup list like any other backup");
    }

    @Test
    void uploadBackup_corruptFile_isRejectedAndNotListed() {
        MockMultipartFile upload = new MockMultipartFile("file", "external.db", "application/octet-stream",
                "not a real sqlite database".getBytes());

        BackupService.BackupResult result = backupService.uploadBackup(upload);

        assertFalse(result.success());
        assertTrue(backupService.listBackups().isEmpty(),
                "a rejected upload must not linger in the backup directory or list");
    }

    @Test
    void uploadBackup_emptyFile_isRejected() {
        MockMultipartFile upload = new MockMultipartFile("file", "empty.db", "application/octet-stream", new byte[0]);

        BackupService.BackupResult result = backupService.uploadBackup(upload);

        assertFalse(result.success());
    }

    @Test
    void uploadBackup_participatesInPruningAlongsideCreatedBackups() throws Exception {
        setMaxBackups(2);
        byte[] validSqliteBytes = Files.readAllBytes(backupDir.resolve(backupService.createBackup().message()));

        Thread.sleep(5);
        backupService.createBackup();
        Thread.sleep(5);
        backupService.uploadBackup(new MockMultipartFile("file", "external.db", "application/octet-stream", validSqliteBytes));

        assertEquals(2, backupService.listBackups().size(), "an upload must be pruned like any other backup once past maxBackups");
    }

    // -------------------------------------------------------------------------
    // pruning
    // -------------------------------------------------------------------------

    @Test
    void createBackup_prunesOldestBackupsBeyondMaxBackups() throws Exception {
        setMaxBackups(2);

        backupService.createBackup();
        Thread.sleep(5); // force distinct millisecond-granularity filenames/order
        backupService.createBackup();
        Thread.sleep(5);
        backupService.createBackup();

        List<BackupService.BackupInfo> backups = backupService.listBackups();
        assertEquals(2, backups.size(), "only the 2 newest backups should remain after pruning");
    }

    @Test
    void createBackup_retentionOfZeroFallsBackToTheDefaultInsteadOfDeletingEverything() throws Exception {
        setMaxBackups(0);

        backupService.createBackup();
        Thread.sleep(5);
        backupService.createBackup();

        assertEquals(2, backupService.listBackups().size(),
                "a zero retention must not wipe the backups, including the one just created");
    }

    // -------------------------------------------------------------------------
    // restoreBackup
    // -------------------------------------------------------------------------

    @Test
    void restoreBackup_happyPath_stagesFileWithoutTouchingLiveDatabaseOrSidecars() throws Exception {
        BackupService.BackupResult created = backupService.createBackup();
        assertTrue(created.success());

        Files.writeString(dbFile, "still-live-content");
        Path walFile = Path.of(dbFile + "-wal");
        Path shmFile = Path.of(dbFile + "-shm");
        Files.writeString(walFile, "still-live-wal");
        Files.writeString(shmFile, "still-live-shm");

        BackupService.BackupResult result = backupService.restoreBackup(created.message());

        assertTrue(result.success(), () -> "restore should succeed: " + result.message());
        assertEquals("still-live-content", Files.readString(dbFile),
                "staging must not touch the live database file -- the swap happens on next startup");
        assertTrue(Files.exists(walFile), "staging must not touch the live -wal sidecar either");
        assertTrue(Files.exists(shmFile), "staging must not touch the live -shm sidecar either");

        Path pending = Path.of(dbFile + PendingRestoreApplier.PENDING_SUFFIX);
        assertTrue(Files.isRegularFile(pending), "a staged file must be written for PendingRestoreApplier to pick up on next startup");
    }

    @Test
    void restoreBackup_rejectsFilenameEscapingBackupDirectoryViaTraversal() {
        BackupService.BackupResult result = backupService.restoreBackup("../evil.db");
        assertFalse(result.success());
    }

    @Test
    void restoreBackup_rejectsAbsolutePathFilename() {
        BackupService.BackupResult result = backupService.restoreBackup(tempDir.resolve("escape.db").toString());
        assertFalse(result.success());
    }

    @Test
    void restoreBackup_rejectsFilenameSmugglingASubdirectory() throws IOException {
        Files.createDirectories(backupDir.resolve("subdir"));
        Path smuggled = backupDir.resolve("subdir").resolve("evil.db");
        Files.writeString(smuggled, "irrelevant -- confinement should reject this before content is ever read");

        BackupService.BackupResult result = backupService.restoreBackup("subdir/evil.db");

        assertFalse(result.success());
    }

    @Test
    void restoreBackup_rejectsNonExistentFilename() {
        BackupService.BackupResult result = backupService.restoreBackup("quickdrop-2020-01-01T00-00-00.db");
        assertFalse(result.success());
    }

    @Test
    void restoreBackup_rejectsCorruptFileWithoutTouchingLiveDatabase() throws IOException {
        Files.createDirectories(backupDir);
        Path corrupt = backupDir.resolve("quickdrop-2020-01-01T00-00-00.db");
        Files.writeString(corrupt, "this is not a valid sqlite database file");
        Files.writeString(dbFile, "original-live-content");

        BackupService.BackupResult result = backupService.restoreBackup(corrupt.getFileName().toString());

        assertFalse(result.success());
        assertEquals("original-live-content", Files.readString(dbFile),
                "a failed integrity check must never touch the live database file");
        assertFalse(Files.exists(Path.of(dbFile + PendingRestoreApplier.PENDING_SUFFIX)),
                "a failed integrity check must not stage anything either");
    }

    // -------------------------------------------------------------------------
    // deleteBackup
    // -------------------------------------------------------------------------

    @Test
    void deleteBackup_removesAnExistingBackupOutsideThePruneCycle() {
        BackupService.BackupResult created = backupService.createBackup();

        BackupService.BackupResult result = backupService.deleteBackup(created.message());

        assertTrue(result.success());
        assertTrue(backupService.listBackups().isEmpty());
    }

    @Test
    void deleteBackup_rejectsFilenameEscapingBackupDirectory() {
        BackupService.BackupResult result = backupService.deleteBackup("../evil.db");
        assertFalse(result.success());
    }

    // -------------------------------------------------------------------------
    // resolveForDownload
    // -------------------------------------------------------------------------

    @Test
    void resolveForDownload_returnsPathForExistingBackup() {
        BackupService.BackupResult created = backupService.createBackup();

        Path resolved = backupService.resolveForDownload(created.message());

        assertNotNull(resolved);
        assertTrue(Files.isRegularFile(resolved));
    }

    @Test
    void resolveForDownload_returnsNullForPathTraversalAttempt() {
        assertNull(backupService.resolveForDownload("../../etc/passwd"));
    }

    @Test
    void resolveForDownload_returnsNullForMissingFile() {
        assertNull(backupService.resolveForDownload("quickdrop-2099-01-01T00-00-00.db"));
    }

    // -------------------------------------------------------------------------
    // updateSchedule / onSettingsChanged
    // -------------------------------------------------------------------------

    @Test
    void updateSchedule_registersATaskWhenEnabled() {
        backupService.updateSchedule("0 0 4 * * *", true);

        assertEquals("0 0 4 * * *", ReflectionTestUtils.getField(backupService, "currentCron"));
        assertEquals(true, ReflectionTestUtils.getField(backupService, "currentScheduleEnabled"));
        assertNotNull(ReflectionTestUtils.getField(backupService, "scheduledTask"));
    }

    @Test
    void updateSchedule_cancelsTaskWhenDisabled() {
        backupService.updateSchedule("0 0 4 * * *", true);

        backupService.updateSchedule("0 0 4 * * *", false);

        assertEquals(false, ReflectionTestUtils.getField(backupService, "currentScheduleEnabled"));
        assertNull(ReflectionTestUtils.getField(backupService, "currentCron"));
        assertNull(ReflectionTestUtils.getField(backupService, "scheduledTask"));
    }

    @Test
    void updateSchedule_reschedulesOnCronChange() {
        backupService.updateSchedule("0 0 4 * * *", true);
        ScheduledFuture<?> firstTask = (ScheduledFuture<?>) ReflectionTestUtils.getField(backupService, "scheduledTask");

        backupService.updateSchedule("0 0 5 * * *", true);

        assertEquals("0 0 5 * * *", ReflectionTestUtils.getField(backupService, "currentCron"));
        assertNotSame(firstTask, ReflectionTestUtils.getField(backupService, "scheduledTask"),
                "a cron change must cancel the old task and register a new one, not reuse the existing future");
    }

    @Test
    void updateSchedule_isANoOpWhenNothingChanged() {
        backupService.updateSchedule("0 0 4 * * *", true);
        Object firstTask = ReflectionTestUtils.getField(backupService, "scheduledTask");

        backupService.updateSchedule("0 0 4 * * *", true);

        assertSame(firstTask, ReflectionTestUtils.getField(backupService, "scheduledTask"),
                "re-applying the same cron/enabled state must not cancel and re-register the task");
    }

    @Test
    void onSettingsChanged_delegatesCronAndEnabledFromEventPayloadToUpdateSchedule() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setBackupScheduleEnabled(true);
        vm.setBackupCron("0 30 4 * * *");
        ApplicationSettingsEntity entity = new ApplicationSettingsEntity(vm);

        backupService.onSettingsChanged(new SettingsChangedEvent(entity));

        assertEquals("0 30 4 * * *", ReflectionTestUtils.getField(backupService, "currentCron"));
        assertEquals(true, ReflectionTestUtils.getField(backupService, "currentScheduleEnabled"));
    }
}
