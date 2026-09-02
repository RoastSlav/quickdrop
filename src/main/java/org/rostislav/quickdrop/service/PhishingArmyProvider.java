package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.util.Fnv1aHash;
import org.rostislav.quickdrop.util.AppPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Domain-blocklist reputation check backed by the
 * <a href="https://phishing.army/">Phishing Army</a> "extended" list — licensed
 * CC BY-NC 4.0, free, no API key required. Ships disabled; see
 * {@link ApplicationSettingsService#acceptReputationProviderTerms} for how it gets turned on.
 *
 * <p>Matches on registrable domain <em>and subdomains</em>: {@code a.b.evil.com} is checked
 * against {@code a.b.evil.com}, {@code b.evil.com}, and {@code evil.com} in turn (bounded at
 * six probes), stopping before it would ever probe a bare TLD like {@code com}.
 */
@Service
public class PhishingArmyProvider extends AbstractHashFeedProvider implements ReputationProvider {
    private static final String FEED_URL = "https://phishing.army/download/phishing_army_blocklist_extended.txt";
    // The feed regenerates every 6h per phishing.army's own documentation -- polling more
    // often than that would only ever re-fetch the same content.
    private static final long MIN_SYNC_INTERVAL_MILLIS = 6L * 60 * 60 * 1000;
    private static final int MAX_SUFFIX_PROBES = 6;

    private final ApplicationSettingsService applicationSettingsService;

    @Autowired
    public PhishingArmyProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService) {
        this(restTemplateBuilder, applicationSettingsService, AppPaths.REPUTATION_FEEDS.resolve("phishing_army.txt"));
    }

    /** Lets tests redirect the on-disk feed file to a {@code @TempDir}. */
    PhishingArmyProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService, Path rawFeedFile) {
        super(restTemplateBuilder, rawFeedFile);
        this.applicationSettingsService = applicationSettingsService;
    }

    @Override
    public String id() {
        return "phishing_army";
    }

    @Override
    public boolean isEnabled() {
        return applicationSettingsService.isReputationPhishingArmyEnabled();
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
            throw new ReputationCheckException("Phishing Army feed has not loaded yet");
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        for (String candidate : suffixCandidates(host.toLowerCase(Locale.ROOT))) {
            if (tier1Contains(Fnv1aHash.hash64(candidate)) && tier2Confirm(candidate)) {
                return true;
            }
        }
        return false;
    }

    static List<String> suffixCandidates(String host) {
        List<String> candidates = new ArrayList<>();
        String current = host;
        while (candidates.size() < MAX_SUFFIX_PROBES) {
            candidates.add(current);
            int dot = current.indexOf('.');
            if (dot < 0) {
                break;
            }
            String rest = current.substring(dot + 1);
            if (!rest.contains(".")) {
                // rest is a bare TLD (e.g. "com") -- never probe that on its own.
                break;
            }
            current = rest;
        }
        return candidates;
    }
}
