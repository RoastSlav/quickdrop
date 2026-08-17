package org.rostislav.quickdrop.service;

import jakarta.annotation.PreDestroy;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.service.AbstractHashFeedProvider.RefreshOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * Keeps the Phishing Army and URLhaus feeds refreshed on a schedule. Mirrors
 * {@link BackupService}'s dynamic-cron pattern (own {@link ThreadPoolTaskScheduler},
 * reschedules on {@link SettingsChangedEvent}) rather than a fixed {@code @Scheduled} cron,
 * since the interval is admin-configurable ({@code reputationFeedCron}).
 *
 * <p>Google Safe Browsing has no feed to refresh here — {@link SafeBrowsingProvider} queries
 * its API live per lookup and caches individual verdicts itself.
 *
 * <p>Every entry point checks the relevant per-provider setting before touching the network,
 * so with the reputation feature shipped disabled (the default), nothing in this class ever
 * makes an HTTP call — including in tests, which all run against default settings unless a
 * test explicitly enables a provider.
 */
@Service
public class ReputationSyncService {
    private static final Logger logger = LoggerFactory.getLogger(ReputationSyncService.class);

    private static final String PHISHING_ARMY_LABEL = "Phishing Army";
    private static final String URLHAUS_LABEL = "URLhaus";

    private final ApplicationSettingsService applicationSettingsService;
    private final PhishingArmyProvider phishingArmyProvider;
    private final UrlhausProvider urlhausProvider;
    private final AnalyticsService analyticsService;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile String currentCron;

    public ReputationSyncService(ApplicationSettingsService applicationSettingsService,
                                 PhishingArmyProvider phishingArmyProvider,
                                 UrlhausProvider urlhausProvider,
                                 @Lazy AnalyticsService analyticsService) {
        this.applicationSettingsService = applicationSettingsService;
        this.phishingArmyProvider = phishingArmyProvider;
        this.urlhausProvider = urlhausProvider;
        this.analyticsService = analyticsService;
        taskScheduler.setPoolSize(1);
        taskScheduler.initialize();
    }

    /**
     * On boot: schedules the recurring sync and, for any provider that's already enabled
     * (e.g. after a restart), kicks off an async initial load so the feed is warm before the
     * first real lookup needs it rather than making that lookup wait or fail closed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        updateSchedule(applicationSettingsService.getReputationFeedCron());
        triggerInitialLoadIfNeeded();
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        updateSchedule(event.settings().getReputationFeedCron());
        // Also covers "the admin just accepted a provider's terms" -- that provider's feed
        // has never loaded yet, so warm it immediately instead of waiting for the next cron tick.
        triggerInitialLoadIfNeeded();
    }

    private void triggerInitialLoadIfNeeded() {
        if (applicationSettingsService.isReputationPhishingArmyEnabled() && !phishingArmyProvider.isLoaded()) {
            CompletableFuture.runAsync(() -> refreshAndLog(PHISHING_ARMY_LABEL, phishingArmyProvider));
        }
        if (applicationSettingsService.isReputationUrlhausEnabled() && !urlhausProvider.isLoaded()) {
            CompletableFuture.runAsync(() -> refreshAndLog(URLHAUS_LABEL, urlhausProvider));
        }
    }

    /**
     * Refreshes one feed and records the outcome in the activity log. A 304 or an
     * interval-floor skip records nothing — those happen on most ticks and would bury the rest.
     */
    void refreshAndLog(String label, AbstractHashFeedProvider provider) {
        RefreshOutcome outcome = provider.refresh();
        try {
            switch (outcome.status()) {
                case UPDATED -> analyticsService.logEvent(EventType.REPUTATION_FEED_UPDATED, null, null,
                        label + ": " + String.format(Locale.ROOT, "%,d", outcome.entryCount()) + " entries");
                case FAILED -> analyticsService.logEvent(EventType.REPUTATION_FEED_FAILED, null, null,
                        label + ": " + outcome.failureReason());
                case UNCHANGED, SKIPPED -> {
                }
            }
        } catch (Exception e) {
            // A logging failure must not escape into the scheduler thread and cancel the job.
            logger.warn("Failed to record reputation feed refresh for {}: {}", label, e.getMessage());
        }
    }

    /**
     * Replaces the scheduled sync job with a new cron expression. No-ops if unchanged.
     *
     * @param cronExpression Spring-compatible 6-field cron expression
     */
    public synchronized void updateSchedule(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            logger.warn("No reputation feed cron expression provided; scheduling skipped");
            return;
        }
        if (cronExpression.equals(currentCron) && scheduledTask != null && !scheduledTask.isCancelled()) {
            return;
        }
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduledTask = taskScheduler.schedule(this::runScheduledSync, new CronTrigger(cronExpression));
        currentCron = cronExpression;
        logger.info("Scheduled reputation feed sync with cron: {}", cronExpression);
    }

    /**
     * Each provider's own {@link AbstractHashFeedProvider#minSyncIntervalMillis()} floor
     * still applies underneath this — an over-frequent cron simply results in most scheduled
     * ticks being no-ops rather than hammering the upstream host.
     */
    private void runScheduledSync() {
        if (applicationSettingsService.isReputationPhishingArmyEnabled()) {
            refreshAndLog(PHISHING_ARMY_LABEL, phishingArmyProvider);
        }
        if (applicationSettingsService.isReputationUrlhausEnabled()) {
            refreshAndLog(URLHAUS_LABEL, urlhausProvider);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        taskScheduler.shutdown();
    }
}
