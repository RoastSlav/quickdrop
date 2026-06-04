package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.LocalStorageService;
import org.rostislav.quickdrop.storage.S3StorageService;
import org.rostislav.quickdrop.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Copies files between the local filesystem and S3 (in either direction).
 *
 * <p>Migration runs in a single background thread. Progress is tracked via atomic
 * counters readable at any time via {@link #getState()}. The source backend is not
 * modified — the operator verifies the migrated data before switching the active backend.
 *
 * <p>Both {@link LocalStorageService} and {@link S3StorageService} are injected
 * directly so the migration can read from one and write to the other regardless of
 * which backend is currently selected in settings.
 */
@Service
public class StorageMigrationService {
    private static final Logger logger = LoggerFactory.getLogger(StorageMigrationService.class);

    public enum MigrationDirection {LOCAL_TO_S3, S3_TO_LOCAL}

    public enum MigrationStatus {IDLE, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED}

    public record MigrationState(
            MigrationStatus status,
            MigrationDirection direction,
            int total,
            int migrated,
            int failed,
            List<String> errors
    ) {}

    private final UploadRepository uploadRepository;
    private final ShareTokenRepository shareTokenRepository;
    private final LocalStorageService localStorage;
    private final S3StorageService s3Storage;

    private volatile MigrationStatus status = MigrationStatus.IDLE;
    private volatile MigrationDirection direction;
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicInteger migrated = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "storage-migration");
        t.setDaemon(true);
        return t;
    });

    public StorageMigrationService(UploadRepository uploadRepository,
                                   ShareTokenRepository shareTokenRepository,
                                   LocalStorageService localStorage,
                                   S3StorageService s3Storage) {
        this.uploadRepository = uploadRepository;
        this.shareTokenRepository = shareTokenRepository;
        this.localStorage = localStorage;
        this.s3Storage = s3Storage;
    }

    /**
     * Returns the number of objects that would be attempted in a migration run.
     *
     * <p>The count is derived entirely from the database (non-deleted upload UUIDs plus
     * share sidecar keys) and performs no storage I/O, making it safe to call as a
     * lightweight pre-flight check.
     *
     * @return total number of keys that would be queued for copying
     */
    public int countObjects() {
        return buildKeyList().size();
    }

    /** Returns the current migration state snapshot. */
    public MigrationState getState() {
        return new MigrationState(status, direction, total.get(), migrated.get(), failed.get(),
                List.copyOf(errors));
    }

    /**
     * Starts a background migration in the given direction.
     *
     * @throws IllegalStateException if a migration is already running
     */
    public synchronized void start(MigrationDirection dir) {
        if (status == MigrationStatus.RUNNING) {
            throw new IllegalStateException("A migration is already in progress");
        }
        this.direction = dir;
        this.status = MigrationStatus.RUNNING;
        total.set(0);
        migrated.set(0);
        failed.set(0);
        errors.clear();
        executor.submit(() -> runMigration(dir));
    }

    private void runMigration(MigrationDirection dir) {
        StorageService source = dir == MigrationDirection.LOCAL_TO_S3 ? localStorage : s3Storage;
        StorageService dest = dir == MigrationDirection.LOCAL_TO_S3 ? s3Storage : localStorage;

        try {
            List<String> allKeys = buildKeyList();
            total.set(allKeys.size());
            logger.info("Storage migration {}: {} objects to copy", dir, allKeys.size());

            for (String key : allKeys) {
                if (!source.exists(key)) {
                    logger.debug("Key {} does not exist on source, skipping", key);
                    total.decrementAndGet();
                    continue;
                }
                try {
                    copyKey(key, source, dest);
                    migrated.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    errors.add(key + ": " + e.getMessage());
                    logger.error("Migration failed for key {}: {}", key, e.getMessage());
                }
            }

            status = failed.get() == 0 ? MigrationStatus.COMPLETED : MigrationStatus.COMPLETED_WITH_ERRORS;
            logger.info("Storage migration complete — migrated: {}, failed: {}", migrated.get(), failed.get());
        } catch (Exception e) {
            status = MigrationStatus.FAILED;
            errors.add("Fatal: " + e.getMessage());
            logger.error("Storage migration aborted: {}", e.getMessage(), e);
        }
    }

    private List<String> buildKeyList() {
        List<String> keys = new ArrayList<>();
        // Main file objects (non-deleted uploads)
        uploadRepository.findAll().stream()
                .filter(u -> !u.deleted)
                .map(u -> u.uuid)
                .forEach(keys::add);
        // Encrypted share sidecars — use a native JOIN query so orphaned tokens
        // (share_token rows whose upload was deleted) are skipped safely.
        keys.addAll(shareTokenRepository.findShareSidecarKeys());
        return keys;
    }

    private void copyKey(String key, StorageService source, StorageService dest) throws IOException {
        try (InputStream in = source.getInputStream(key);
             OutputStream out = dest.getOutputStream(key)) {
            in.transferTo(out);
        }
    }
}
