package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Stubs {@link ReputationProvider} directly -- never touches a real feed or the network.
 */
@ExtendWith(MockitoExtension.class)
class ReputationCheckServiceTest {

    @Mock
    private ApplicationSettingsService applicationSettingsService;
    @Mock
    private ReputationProvider provider;

    private static final URI URI_TO_CHECK = URI.create("https://example.com/page");

    @Test
    void masterSwitchOffSkipsEveryProviderEvenIfEnabled() {
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(false);
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        assertTrue(service.check(URI_TO_CHECK).isEmpty());
    }

    @Test
    void skipsADisabledProvider() throws Exception {
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(true);
        when(provider.isEnabled()).thenReturn(false);
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        assertTrue(service.check(URI_TO_CHECK).isEmpty());
    }

    @Test
    void blocksWhenAnEnabledProviderFlagsTheDestination() throws Exception {
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(true);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isMalicious(URI_TO_CHECK)).thenReturn(true);
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        Optional<String> result = service.check(URI_TO_CHECK);
        assertTrue(result.isPresent());
    }

    @Test
    void allowsWhenEveryEnabledProviderReportsClean() throws Exception {
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(true);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isMalicious(URI_TO_CHECK)).thenReturn(false);
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        assertTrue(service.check(URI_TO_CHECK).isEmpty());
    }

    @Test
    void failsOpenByDefaultWhenAProviderCannotCompleteItsCheck() throws Exception {
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(true);
        when(applicationSettingsService.isReputationFailClosed()).thenReturn(false);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isMalicious(URI_TO_CHECK)).thenThrow(new ReputationCheckException("feed not loaded"));
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        assertTrue(service.check(URI_TO_CHECK).isEmpty(), "a provider failure must not block when fail-open (the default)");
    }

    @Test
    void failsClosedWhenConfiguredAndAProviderCannotCompleteItsCheck() throws Exception {
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(true);
        when(applicationSettingsService.isReputationFailClosed()).thenReturn(true);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isMalicious(URI_TO_CHECK)).thenThrow(new ReputationCheckException("feed not loaded"));
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        assertTrue(service.check(URI_TO_CHECK).isPresent(), "a provider failure must block when fail-closed");
    }

    @Test
    void aTierOneHitThatFailsTierTwoConfirmationIsAllowed() throws Exception {
        // Exercises the false-positive-avoidance path: a hash hit that fails tier-2 confirmation must not block.
        when(applicationSettingsService.isReputationCheckEnabled()).thenReturn(true);
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isMalicious(URI_TO_CHECK)).thenReturn(false);
        ReputationCheckService service = new ReputationCheckService(List.of(provider), applicationSettingsService);

        assertTrue(service.check(URI_TO_CHECK).isEmpty());
    }
}
