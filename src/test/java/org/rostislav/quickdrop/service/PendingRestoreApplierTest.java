package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PendingRestoreApplierTest {

    @TempDir
    private Path tempDir;

    @Test
    void applyIfPending_noOpsWhenNothingStaged() throws Exception {
        Path dbFile = tempDir.resolve("quickdrop.db");
        Files.writeString(dbFile, "untouched");

        PendingRestoreApplier.applyIfPending(dbFile);

        assertEquals("untouched", Files.readString(dbFile));
    }

    @Test
    void applyIfPending_swapsInStagedFileAndCleansUpSidecarsAndMarker() throws Exception {
        Path dbFile = tempDir.resolve("quickdrop.db");
        Path walFile = Path.of(dbFile + "-wal");
        Path shmFile = Path.of(dbFile + "-shm");
        Path pending = Path.of(dbFile + PendingRestoreApplier.PENDING_SUFFIX);
        Files.writeString(dbFile, "stale-live-content");
        Files.writeString(walFile, "stale-wal");
        Files.writeString(shmFile, "stale-shm");
        Files.writeString(pending, "restored-content");

        PendingRestoreApplier.applyIfPending(dbFile);

        assertEquals("restored-content", Files.readString(dbFile));
        assertFalse(Files.exists(walFile), "stale -wal must be removed so it isn't replayed against the restored file");
        assertFalse(Files.exists(shmFile), "stale -shm must be removed");
        assertFalse(Files.exists(pending), "the staging marker must be consumed");
    }

    @Test
    void restoreBackup_thenApplyIfPending_endToEndRoundTrip() throws Exception {
        Path dbFile = tempDir.resolve("quickdrop.db");
        Path backupDir = tempDir.resolve("db-backups");
        Files.writeString(dbFile, "original-live-content");

        // restoreBackup validates the candidate via PRAGMA quick_check, so it must be a real
        // SQLite database, not an arbitrary string. Neither restoreBackup nor applyIfPending
        // touches applicationSettingsService/analyticsService/dataSource -- only createBackup() does.
        Path backupFile = backupDir.resolve("quickdrop-2026-01-01T00-00-00-000.db");
        Files.createDirectories(backupDir);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + backupFile.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t(x INTEGER)");
        }
        byte[] backupBytes = Files.readAllBytes(backupFile);

        BackupService backupService = new BackupService(null, null, null, dbFile, backupDir);
        backupService.restoreBackup(backupFile.getFileName().toString());

        assertEquals("original-live-content", Files.readString(dbFile),
                "restoreBackup alone must not have touched the live file yet");

        PendingRestoreApplier.applyIfPending(dbFile);

        assertArrayEquals(backupBytes, Files.readAllBytes(dbFile),
                "applying the staged restore must swap in the backup's exact content");
    }
}
