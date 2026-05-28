package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes {@link EventType#STARTUP} and {@link EventType#SHUTDOWN} entries
 * to the activity log when the application starts and stops.
 *
 * <p>{@link AnalyticsService} is injected lazily to avoid a circular dependency
 * during context initialisation.
 */
@Component
public class AppLifecycleListener {
    private static final Logger logger = LoggerFactory.getLogger(AppLifecycleListener.class);

    private final AnalyticsService analyticsService;

    public AppLifecycleListener(@Lazy AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Fires after the application context is fully refreshed and ready to serve requests.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            analyticsService.logEvent(EventType.STARTUP, null, null);
            logger.info("Application startup logged to activity log.");
        } catch (Exception e) {
            logger.error("Failed to log startup event", e);
        }
    }

    /**
     * Fires when the application context begins shutting down.
     * The database connection pool is still open at this point.
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        try {
            analyticsService.logEvent(EventType.SHUTDOWN, null, null);
            logger.info("Application shutdown logged to activity log.");
        } catch (Exception e) {
            logger.error("Failed to log shutdown event", e);
        }
    }
}
