package org.rostislav.quickdrop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Consults every enabled {@link ReputationProvider} for a destination, applying the
 * {@code reputationCheckEnabled} master switch and {@code reputationFailClosed} policy.
 * Called from {@link LinkGuard} on both creation and every resolve — see that class's
 * javadoc for why a one-time check at creation isn't enough.
 *
 * <p>Providers are collected via {@code List<ReputationProvider>} constructor injection —
 * every {@code @Service} implementing the interface is picked up automatically, the same
 * pattern {@code DelegatingStorageService} uses for storage backends. Adding a fourth
 * provider later means writing the class and nothing else here.
 *
 * <p><strong>Fail-open by default.</strong> A provider that can't complete a check (feed not
 * loaded, API unreachable) throws {@link ReputationCheckException} rather than returning a
 * verdict; by default that failure is logged and checking moves on to the next provider,
 * because taking down the whole shortener over a third-party outage is worse than an
 * unverified link going through. Admins who prefer the stricter posture can turn on
 * {@code reputationFailClosed}, which blocks on the first provider failure instead.
 */
@Service
public class ReputationCheckService {
    private static final Logger logger = LoggerFactory.getLogger(ReputationCheckService.class);

    private final List<ReputationProvider> providers;
    private final ApplicationSettingsService applicationSettingsService;

    public ReputationCheckService(List<ReputationProvider> providers, ApplicationSettingsService applicationSettingsService) {
        this.providers = providers;
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * @param uri the destination to check (already normalized/absolute)
     * @return empty if allowed (checking disabled, no provider flagged it, or a failure was
     *         allowed through fail-open); a plain-language rejection message if blocked
     */
    public Optional<String> check(URI uri) {
        if (!applicationSettingsService.isReputationCheckEnabled()) {
            return Optional.empty();
        }
        boolean failClosed = applicationSettingsService.isReputationFailClosed();
        for (ReputationProvider provider : providers) {
            if (!provider.isEnabled()) {
                continue;
            }
            try {
                if (provider.isMalicious(uri)) {
                    logger.info("Destination flagged by reputation provider {}: {}", provider.id(), uri.getHost());
                    return Optional.of("This destination has been flagged as unsafe by a threat-intelligence provider.");
                }
            } catch (ReputationCheckException e) {
                logger.warn("Reputation check failed for provider {}: {}", provider.id(), e.getMessage());
                if (failClosed) {
                    return Optional.of("Destination safety could not be verified right now. Please try again shortly.");
                }
            }
        }
        return Optional.empty();
    }
}
