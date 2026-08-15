package org.rostislav.quickdrop.service;

import java.net.URI;

/**
 * One threat-intelligence source consulted by {@link ReputationCheckService}. Implementations
 * are plain Spring beans — {@link ReputationCheckService} collects every one of them via
 * {@code List<ReputationProvider>} constructor injection, the same auto-collection pattern
 * {@code DelegatingStorageService} uses for storage backends.
 *
 * <p>All three implementations ({@link PhishingArmyProvider}, {@link UrlhausProvider},
 * {@link SafeBrowsingProvider}) ship disabled — see {@link ApplicationSettingsService#acceptReputationProviderTerms}
 * for the licence-acceptance gate that is the only way to turn one on.
 */
public interface ReputationProvider {
    /**
     * @return a stable identifier matching {@link ApplicationSettingsService#acceptReputationProviderTerms}'s
     *         {@code providerId} values ({@code "phishing_army"}, {@code "urlhaus"}, {@code "safe_browsing"})
     */
    String id();

    /**
     * @return {@code true} if this provider is enabled and should be consulted. Individual
     *         providers are responsible for checking both the per-provider setting and the
     *         master {@code reputationCheckEnabled} switch is left to the caller.
     */
    boolean isEnabled();

    /**
     * Checks whether {@code uri} is a known-malicious destination per this provider.
     *
     * @param uri the destination to check (already normalized/absolute)
     * @return {@code true} if flagged malicious, {@code false} if checked and clean
     * @throws ReputationCheckException if the check could not be completed at all (feed not
     *                                   yet loaded, API unreachable) — this is distinct from a
     *                                   completed "not found" and is what {@code reputationFailClosed}
     *                                   governs
     */
    boolean isMalicious(URI uri) throws ReputationCheckException;
}
