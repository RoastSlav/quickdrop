package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.storage.DelegatingStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintains a background health-check result for the active storage backend.
 *
 * <p>A {@link Scheduled} probe fires every 30 seconds (with a matching initial delay
 * to let the application fully start). The result is stored in a {@code volatile}
 * field so any thread can read it without synchronisation cost.
 *
 * <p>Delegates the actual reachability probe to {@link DelegatingStorageService#isReachable()},
 * which forwards it to the currently active backend. Switching backends clears any stale
 * "down" state on the next probe cycle.
 */
@Service
public class StorageHealthService {
    private static final Logger logger = LoggerFactory.getLogger(StorageHealthService.class);

    private final DelegatingStorageService delegatingService;
    private final NotificationService notificationService;

    /**
     * Optimistic initial state — avoids a false "storage down" banner during the first
     * 10-second window before the first probe completes.
     */
    private volatile boolean healthy = true;

    public StorageHealthService(DelegatingStorageService delegatingService,
                                @Lazy NotificationService notificationService) {
        this.delegatingService = delegatingService;
        this.notificationService = notificationService;
    }

    /**
     * Runs an immediate probe as soon as the application is fully started so any
     * "backend down" banner appears right away rather than after the first
     * scheduled delay.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread t = new Thread(this::checkHealth, "storage-health-initial");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Re-probes immediately after any settings save, so a backend change (e.g. S3 → LOCAL)
     * takes effect without waiting up to 30 s for the next scheduled cycle.
     */
    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        recheck();
    }

    /**
     * Probes the active storage backend every 30 seconds.
     * The initial delay is aligned to the fixed delay since startup is now
     * handled by {@link #onApplicationReady()}.
     * Logs on transitions between healthy and unhealthy states.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void checkHealth() {
        boolean reachable = delegatingService.isReachable();
        if (healthy && !reachable) {
            logger.warn("Storage health check: backend {} is unreachable", delegatingService.getBackend());
            notificationService.notifySystemEvent(EventType.STORAGE_BACKEND_DOWN);
        } else if (!healthy && reachable) {
            logger.info("Storage health check: backend {} is reachable again", delegatingService.getBackend());
            notificationService.notifySystemEvent(EventType.STORAGE_BACKEND_UP);
        }
        healthy = reachable;
    }

    /**
     * Returns {@code true} when the last health probe failed for the active backend.
     *
     * @return {@code true} if storage is currently unreachable; {@code false} if healthy
     */
    public boolean isStorageDown() {
        return !healthy;
    }

    /**
     * Optimistically clears any stale "down" state and immediately re-probes the active
     * backend in a background thread.
     *
     * <p>Called automatically by {@link #onSettingsChanged} whenever settings are saved
     * (e.g. the active backend changes) so uploads are not blocked for up to 30 seconds
     * while waiting for the next scheduled probe cycle.
     */
    public void recheck() {
        healthy = true;
        Thread t = new Thread(this::checkHealth, "storage-health-recheck");
        t.setDaemon(true);
        t.start();
    }
}
