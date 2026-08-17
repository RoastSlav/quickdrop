package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.model.EventType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.rostislav.quickdrop.service.AbstractHashFeedProvider.RefreshOutcome;

class ReputationSyncServiceTest {

    private PhishingArmyProvider provider;
    private AnalyticsService analyticsService;
    private ReputationSyncService syncService;

    @BeforeEach
    void setUp() {
        provider = mock(PhishingArmyProvider.class);
        analyticsService = mock(AnalyticsService.class);
        syncService = new ReputationSyncService(mock(ApplicationSettingsService.class), provider,
                mock(UrlhausProvider.class), analyticsService);
    }

    @Test
    void updatedFeedIsRecordedWithProviderAndEntryCount() {
        when(provider.refresh()).thenReturn(RefreshOutcome.updated(156264));

        syncService.refreshAndLog("Phishing Army", provider);

        verify(analyticsService).logEvent(eq(EventType.REPUTATION_FEED_UPDATED), isNull(), isNull(),
                eq("Phishing Army: 156,264 entries"));
    }

    @Test
    void failedRefreshIsRecordedWithReason() {
        when(provider.refresh()).thenReturn(RefreshOutcome.failed("HTTP 503"));

        syncService.refreshAndLog("URLhaus", provider);

        verify(analyticsService).logEvent(eq(EventType.REPUTATION_FEED_FAILED), isNull(), isNull(),
                eq("URLhaus: HTTP 503"));
    }

    /**
     * A 304 or an interval-floor skip happens on most scheduler ticks; recording those would
     * bury every other event in the activity log.
     */
    @Test
    void unchangedAndSkippedRefreshesAreNotRecorded() {
        when(provider.refresh()).thenReturn(RefreshOutcome.unchanged(), RefreshOutcome.skipped());

        syncService.refreshAndLog("Phishing Army", provider);
        syncService.refreshAndLog("Phishing Army", provider);

        verify(analyticsService, never()).logEvent(any(), any(), any(), any());
    }

    @Test
    void loggingFailureDoesNotEscapeToTheScheduler() {
        when(provider.refresh()).thenReturn(RefreshOutcome.updated(10));
        doThrow(new RuntimeException("db down"))
                .when(analyticsService).logEvent(any(), any(), any(), any());

        syncService.refreshAndLog("Phishing Army", provider);
    }
}
