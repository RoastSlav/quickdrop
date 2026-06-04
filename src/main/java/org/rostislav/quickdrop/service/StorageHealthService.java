package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.storage.DelegatingStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintains a background health-check result for the active storage backend.
 *
 * <p>A {@link Scheduled} probe fires every 30 seconds (with a 10-second initial delay
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

    /**
     * Optimistic initial state — avoids a false "storage down" banner during the first
     * 10-second window before the first probe completes.
     */
    private volatile boolean healthy = true;

    public StorageHealthService(DelegatingStorageService delegatingService) {
        this.delegatingService = delegatingService;
    }

    /**
     * Probes the active storage backend every 30 seconds.
     * Logs on transitions between healthy and unhealthy states.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void checkHealth() {
        boolean reachable = delegatingService.isReachable();
        if (healthy && !reachable) {
            logger.warn("Storage health check: backend {} is unreachable", delegatingService.getBackend());
        } else if (!healthy && reachable) {
            logger.info("Storage health check: backend {} is reachable again", delegatingService.getBackend());
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
}
