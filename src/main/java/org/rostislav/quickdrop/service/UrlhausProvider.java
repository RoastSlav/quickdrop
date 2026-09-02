package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.util.Fnv1aHash;
import org.rostislav.quickdrop.util.AppPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * URL-blocklist reputation check backed by <a href="https://urlhaus.abuse.ch/">URLhaus</a>
 * (abuse.ch)'s "online" dataset — currently-active malware-distribution URLs, a few thousand
 * entries, refreshed frequently. Requires a free Auth-Key from {@code auth.abuse.ch}; fair-use,
 * non-commercial licence. Ships disabled; see
 * {@link ApplicationSettingsService#acceptReputationProviderTerms}.
 *
 * <p><strong>Exact URL match only, deliberately not host match.</strong> URLhaus entries
 * frequently sit on legitimate-but-compromised hosts (a hacked WordPress site serving malware
 * from one path, say) — matching by host would block every other, innocent page on that same
 * domain. Matching the exact reported URL avoids that false-positive class at the cost of
 * missing other malicious paths on the same compromised host, which is the right trade-off for
 * a check that runs automatically against user-submitted links.
 */
@Service
public class UrlhausProvider extends AbstractHashFeedProvider implements ReputationProvider {
    private static final String FEED_URL = "https://urlhaus.abuse.ch/downloads/csv_online/";
    // abuse.ch's documented floor for automated polling of this feed.
    private static final long MIN_SYNC_INTERVAL_MILLIS = 5L * 60 * 1000;

    private final ApplicationSettingsService applicationSettingsService;

    @Autowired
    public UrlhausProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService) {
        this(restTemplateBuilder, applicationSettingsService, AppPaths.REPUTATION_FEEDS.resolve("urlhaus.csv"));
    }

    /** Lets tests redirect the on-disk feed file to a {@code @TempDir}. */
    UrlhausProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService, Path rawFeedFile) {
        super(restTemplateBuilder, rawFeedFile);
        this.applicationSettingsService = applicationSettingsService;
    }

    @Override
    public String id() {
        return "urlhaus";
    }

    @Override
    public boolean isEnabled() {
        String authKey = applicationSettingsService.getUrlhausAuthKey();
        return applicationSettingsService.isReputationUrlhausEnabled() && authKey != null && !authKey.isBlank();
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
    protected Map<String, String> extraHeaders() {
        String authKey = applicationSettingsService.getUrlhausAuthKey();
        return authKey == null || authKey.isBlank() ? Map.of() : Map.of("Auth-Key", authKey);
    }

    @Override
    protected String normalizeLine(String rawLine) {
        String trimmed = rawLine.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String url = extractUrlColumn(trimmed);
        return url == null ? null : canonicalize(url);
    }

    /**
     * Pulls the {@code url} field (3rd column) out of one quoted-CSV row. URLhaus's own values
     * never contain embedded commas or quotes (they're already-escaped URLs), so a plain split
     * on {@code ","} between quoted fields is sufficient — no general CSV-quoting edge cases
     * to handle here.
     */
    private static String extractUrlColumn(String csvLine) {
        String[] fields = csvLine.split("\",\"");
        if (fields.length < 3) {
            return null;
        }
        String url = fields[2];
        if (url.startsWith("\"")) {
            url = url.substring(1);
        }
        if (url.endsWith("\"")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isBlank() ? null : url;
    }

    private static String canonicalize(String url) {
        String trimmed = url.strip().toLowerCase(Locale.ROOT);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public boolean isMalicious(URI uri) throws ReputationCheckException {
        if (!isLoaded()) {
            throw new ReputationCheckException("URLhaus feed has not loaded yet");
        }
        String candidate = canonicalize(uri.toString());
        return tier1Contains(Fnv1aHash.hash64(candidate)) && tier2Confirm(candidate);
    }
}
