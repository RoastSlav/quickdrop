package org.rostislav.quickdrop.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

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

    private final ApplicationSettingsService applicationSettingsService;
    private final PhishingArmyProvider phishingArmyProvider;
    private final UrlhausProvider urlhausProvider;
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile String currentCron;

    public ReputationSyncService(ApplicationSettingsService applicationSettingsService,
                                 PhishingArmyProvider phishingArmyProvider,
                                 UrlhausProvider urlhausProvider) {
        this.applicationSettingsService = applicationSettingsService;
        this.phishingArmyProvider = phishingArmyProvider;
        this.urlhausProvider = urlhausProvider;
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
            CompletableFuture.runAsync(phishingArmyProvider::refresh);
        }
        if (applicationSettingsService.isReputationUrlhausEnabled() && !urlhausProvider.isLoaded()) {
            CompletableFuture.runAsync(urlhausProvider::refresh);
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
            phishingArmyProvider.refresh();
        }
        if (applicationSettingsService.isReputationUrlhausEnabled()) {
            urlhausProvider.refresh();
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
