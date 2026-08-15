package org.rostislav.quickdrop.service;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reputation check against Google Safe Browsing v5's {@code hashes:search} endpoint.
 * Ships disabled; see {@link ApplicationSettingsService#acceptReputationProviderTerms}.
 *
 * <p><strong>Only a truncated hash prefix ever leaves this instance — never the URL itself.</strong>
 * The destination is SHA-256 hashed and only the first 4 bytes of that digest are sent to
 * Google; a match is confirmed locally by checking whether any of the (possibly several)
 * full hashes Google returns for that prefix equals our own full digest. This is the
 * long-standing Safe Browsing hash-prefix protocol specifically so the API provider never
 * learns which exact URLs are being checked. {@code urls:search} (which sends the full URL)
 * is deliberately not used here for that reason.
 *
 * <p><strong>API-shape caveat:</strong> the v5 API was newer/less stable at the time this was
 * written than v4; the request/response shape below (GET {@code /v5/hashes:search} with
 * repeated {@code hashPrefixes} query params, base64url-encoded, and a {@code fullHashes[]}
 * response array of base64 digests) follows Google's published hash-prefix pattern but has
 * not been exercised against a live key. Verify against current Safe Browsing documentation
 * before relying on this in production, and adjust {@link #parseResponse} if the shape has
 * changed.
 *
 * <p>Tier-0 cache: verdicts are cached by canonical URL in a small bounded LRU so repeat
 * lookups of the same destination don't re-spend API quota, honouring the response's
 * {@code cacheDurationSeconds} where present.
 */
@Service
public class SafeBrowsingProvider implements ReputationProvider {
    private static final String API_URL = "https://safebrowsing.googleapis.com/v5/hashes:search";
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final long DEFAULT_CACHE_DURATION_MILLIS = Duration.ofMinutes(5).toMillis();

    private final ApplicationSettingsService applicationSettingsService;
    private final RestTemplate restTemplate;
    private final Map<String, CachedVerdict> verdictCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedVerdict> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public SafeBrowsingProvider(RestTemplateBuilder restTemplateBuilder, ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() {
        return "safe_browsing";
    }

    @Override
    public boolean isEnabled() {
        String apiKey = applicationSettingsService.getSafeBrowsingApiKey();
        return applicationSettingsService.isReputationSafeBrowsingEnabled() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean isMalicious(URI uri) throws ReputationCheckException {
        String canonicalUrl = uri.toString();

        CachedVerdict cached = verdictCache.get(canonicalUrl);
        if (cached != null && cached.expiresAtEpochMillis > System.currentTimeMillis()) {
            return cached.malicious;
        }

        String apiKey = applicationSettingsService.getSafeBrowsingApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ReputationCheckException("Safe Browsing API key is not configured");
        }

        byte[] fullHash = sha256(canonicalUrl);
        byte[] prefixBytes = Base64.getUrlEncoder().withoutPadding().encode(Arrays.copyOf(fullHash, 4));
        String prefixParam = new String(prefixBytes, StandardCharsets.US_ASCII);

        URI requestUri = UriComponentsBuilder.fromUriString(API_URL)
                .queryParam("key", apiKey)
                .queryParam("hashPrefixes", prefixParam)
                .build()
                .toUri();

        try {
            ResponseEntity<SafeBrowsingResponse> response = restTemplate.getForEntity(requestUri, SafeBrowsingResponse.class);
            SafeBrowsingResponse body = response.getBody();
            boolean malicious = parseResponse(body, fullHash);
            long ttl = body != null && body.cacheDurationSeconds != null
                    ? Duration.ofSeconds(body.cacheDurationSeconds).toMillis()
                    : DEFAULT_CACHE_DURATION_MILLIS;
            verdictCache.put(canonicalUrl, new CachedVerdict(malicious, System.currentTimeMillis() + ttl));
            return malicious;
        } catch (RestClientException e) {
            throw new ReputationCheckException("Safe Browsing API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * A hash-prefix match only narrows candidates down to a small set sharing that prefix;
     * the real destination is only actually flagged if one of the returned full hashes
     * equals our own full SHA-256 digest.
     */
    private static boolean parseResponse(SafeBrowsingResponse body, byte[] ourFullHash) {
        if (body == null || body.fullHashes == null) {
            return false;
        }
        for (FullHashEntry entry : body.fullHashes) {
            if (entry.fullHash == null) {
                continue;
            }
            byte[] candidate = Base64.getDecoder().decode(entry.fullHash);
            if (Arrays.equals(candidate, ourFullHash)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM per the platform spec.
            throw new IllegalStateException(e);
        }
    }

    private record CachedVerdict(boolean malicious, long expiresAtEpochMillis) {
    }

    /** Minimal deserialization target for the {@code hashes:search} response body. */
    private static final class SafeBrowsingResponse {
        public List<FullHashEntry> fullHashes;
        public Long cacheDurationSeconds;
    }

    private static final class FullHashEntry {
        public String fullHash;
        public List<String> threatTypes;
    }
}
