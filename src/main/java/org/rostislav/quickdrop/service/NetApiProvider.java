package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.util.AppPaths;
import org.rostislav.quickdrop.util.Fnv1aHash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Domain-blocklist reputation check backed by the
 * <a href="https://netapi.com/compromised-urls/">NetAPI compromised domains</a> list —
 * licensed CC BY 4.0, free, no API key required. Ships disabled; see
 * {@link ApplicationSettingsService#acceptReputationProviderTerms} for how it gets turned on.
 *
 * <p>Matches a host and its parent domains the same way {@link PhishingArmyProvider} does.
 */
@Service
public class NetApiProvider extends AbstractHashFeedProvider implements ReputationProvider {
    private static final String FEED_URL = "https://netapi.com/api2/netapi-compromised-domains.txt";
    // The list is rebuilt hourly upstream, so a shorter interval would only re-fetch the same content.
    private static final long MIN_SYNC_INTERVAL_MILLIS = 60L * 60 * 1000;

    private final ApplicationSettingsService applicationSettingsService;

    @Autowired
    public NetApiProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService) {
        this(restTemplateBuilder, applicationSettingsService, AppPaths.REPUTATION_FEEDS.resolve("netapi.txt"));
    }

    /** Lets tests redirect the on-disk feed file to a {@code @TempDir}. */
    NetApiProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService, Path rawFeedFile) {
        super(restTemplateBuilder, rawFeedFile);
        this.applicationSettingsService = applicationSettingsService;
    }

    @Override
    public String id() {
        return "netapi";
    }

    @Override
    public boolean isEnabled() {
        return applicationSettingsService.isReputationNetApiEnabled();
    }

    @Override
    protected String feedUrl() {
        return FEED_URL;
    }

    @Override
    protected long minSyncIntervalMillis() {
        return MIN_SYNC_INTERVAL_MILLIS;
    }

    @Override
    protected String normalizeLine(String rawLine) {
        String trimmed = rawLine.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        return trimmed;
    }

    @Override
    public boolean isMalicious(URI uri) throws ReputationCheckException {
        if (!isLoaded()) {
            throw new ReputationCheckException("NetAPI feed has not loaded yet");
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        for (String candidate : PhishingArmyProvider.suffixCandidates(host.toLowerCase(Locale.ROOT))) {
            if (tier1Contains(Fnv1aHash.hash64(candidate)) && tier2Confirm(candidate)) {
                return true;
            }
        }
        return false;
    }
}
