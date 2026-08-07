package org.rostislav.quickdrop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Applies a database restore staged by {@link BackupService#restoreBackup(String)}, if any, at
 * JVM startup before any connection pool exists — {@link BackupService} can't safely replace the
 * live db/wal/shm files itself while the app's own connection pool has them open (this fails
 * outright on Windows, and is unsafe even where the OS allows it).
 */
public final class PendingRestoreApplier {
    private static final Logger logger = LoggerFactory.getLogger(PendingRestoreApplier.class);

    static final String PENDING_SUFFIX = ".pending-restore";

    // The previous process (the one that staged this restore) may still be releasing its file
    // handles on the same db/wal/shm files when this one starts -- observed in practice even a
    // couple of seconds after that process's exit call returned. Retry instead of failing outright.
    private static final int MAX_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MILLIS = 300;

    private PendingRestoreApplier() {
    }

    /** No-ops if no restore is staged for {@code dbFile}. */
    public static void applyIfPending(Path dbFile) throws IOException {
        Path pending = Path.of(dbFile + PENDING_SUFFIX);
        if (!Files.exists(pending)) {
            return;
        }
        retryOnLock(() -> Files.deleteIfExists(Path.of(dbFile + "-wal")));
        retryOnLock(() -> Files.deleteIfExists(Path.of(dbFile + "-shm")));
        retryOnLock(() -> Files.move(pending, dbFile, StandardCopyOption.REPLACE_EXISTING));
        logger.info("Applied a staged database restore to {}", dbFile);
    }

    private interface FileOperation {
        void run() throws IOException;
    }

    private static void retryOnLock(FileOperation operation) throws IOException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                operation.run();
                return;
            } catch (FileSystemException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                logger.warn("File still in use by a previous process, retrying ({}/{})", attempt, MAX_ATTEMPTS);
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
