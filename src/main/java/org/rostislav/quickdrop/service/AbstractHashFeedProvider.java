package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.util.Fnv1aHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Shared download/parse/hash/match machinery for a text-based threat-intelligence feed
 * ({@link PhishingArmyProvider}, {@link UrlhausProvider}). Google Safe Browsing has no feed
 * of its own ({@link SafeBrowsingProvider} queries an API live per lookup instead) so it does
 * not extend this class.
 *
 * <p><strong>Two-tier matching.</strong> Feed entries are stored as a sorted {@code long[]} of
 * {@link Fnv1aHash} values rather than a {@code Set<String>} — at Phishing Army's scale
 * (hundreds of thousands of domains) a {@code HashSet<String>} costs roughly 140 bytes per
 * entry once String/char-array/HashMap-node overhead is counted, versus 8 bytes per entry
 * here: a ~17x reduction, and the difference between "fine in a 512 MB container" and not.
 * {@link Arrays#binarySearch} gives sub-microsecond lookups over the contiguous primitive
 * array. A 64-bit hash's collision probability at this scale (~4x10⁻⁹) is low but not zero,
 * and a false positive here means blocking a legitimate link — the one failure an admin would
 * actually notice — so every tier-1 hit is confirmed against the real feed text
 * ({@link #tier2Confirm}) before anything is reported malicious. That confirmation only ever
 * runs on the rare hit, never on the ~100% of lookups that are clean, so it costs nothing on
 * the hot path.
 *
 * <p><strong>Loading.</strong> {@link #refresh()} is safe to call from a background scheduler
 * or an ad-hoc trigger — it enforces {@link #minSyncIntervalMillis()} itself (so a
 * misconfigured cron can't hammer the upstream host), sends conditional-request headers so an
 * unchanged feed costs one cheap round trip, caps the accepted response size at
 * {@value #MAX_FEED_BYTES} bytes, and never throws: on any failure it logs a warning and keeps
 * serving whatever was last loaded successfully, per the fail-open posture described in
 * {@link ReputationCheckService}.
 */
public abstract class AbstractHashFeedProvider {
    private static final Logger logger = LoggerFactory.getLogger(AbstractHashFeedProvider.class);
    private static final long MAX_FEED_BYTES = 50L * 1024 * 1024;

    private final RestTemplate restTemplate;
    private final Path rawFeedFile;

    private volatile long[] sortedHashes = new long[0];
    private volatile boolean loadedSuccessfully = false;
    private volatile long lastSyncAttemptEpochMillis = 0;
    private volatile String lastETag;
    private volatile String lastModifiedHeader;
    private volatile boolean hydratedFromDisk = false;

    protected AbstractHashFeedProvider(RestTemplateBuilder restTemplateBuilder, Path rawFeedFile) {
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
        this.rawFeedFile = rawFeedFile;
    }

    /** @return the feed's download URL */
    protected abstract String feedUrl();

    /** @return minimum milliseconds between real downloads, regardless of how often {@link #refresh()} is called */
    protected abstract long minSyncIntervalMillis();

    /** @return extra request headers (e.g. an API auth key); empty map if none needed */
    protected Map<String, String> extraHeaders() {
        return Map.of();
    }

    /**
     * Parses one raw line from the feed into the canonical string that gets hashed and later
     * compared for tier-2 confirmation, or {@code null} to skip the line (comments, blanks,
     * malformed rows).
     */
    protected abstract String normalizeLine(String rawLine);

    /** @return {@code true} once at least one successful sync has completed */
    public boolean isLoaded() {
        return loadedSuccessfully;
    }

    /**
     * Test-only hook: runs the real parse/hash/write-to-disk pipeline {@link #refresh()} uses,
     * without a network call — lets subclass tests exercise genuine tier-1 + tier-2 matching
     * against known content instead of mocking {@link Arrays#binarySearch} or file I/O.
     */
    void loadForTest(byte[] body) throws IOException {
        loadFromBytes(body);
        loadedSuccessfully = true;
    }

    protected boolean tier1Contains(long hash) {
        return Arrays.binarySearch(sortedHashes, hash) >= 0;
    }

    /**
     * Confirms an exact tier-1 hit against the real feed text on disk — only ever called after
     * a hash match, so this scan is invisible on the ~100% of lookups that are clean.
     */
    protected boolean tier2Confirm(String candidate) {
        Path file = rawFeedFile;
        if (file == null || !Files.exists(file)) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (candidate.equals(normalizeLine(line))) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("[{}] tier-2 confirmation scan failed: {}", feedUrl(), e.getMessage());
        }
        return false;
    }

    /**
     * Downloads and reloads the feed if {@link #minSyncIntervalMillis()} has elapsed since the
     * last attempt (successful or not — a failing upstream doesn't get hammered either).
     * Never throws; failures are reported in the returned outcome and the previously-loaded
     * feed keeps serving.
     *
     * @return what actually happened, so callers can record it (see
     *         {@link ReputationSyncService}); never {@code null}
     */
    public synchronized RefreshOutcome refresh() {
        // Seed from the last downloaded copy first. Without this, every JVM start began with
        // lastSyncAttemptEpochMillis = 0 and no cached validators, so the interval floor never
        // applied on boot and the whole feed was re-downloaded on every restart.
        hydrateFromDisk();
        long now = System.currentTimeMillis();
        if (now - lastSyncAttemptEpochMillis < minSyncIntervalMillis()) {
            return RefreshOutcome.skipped();
        }
        lastSyncAttemptEpochMillis = now;
        try {
            FetchResult result = fetch();
            if (result.notModified()) {
                logger.debug("[{}] feed unchanged (304)", feedUrl());
                return RefreshOutcome.unchanged();
            }
            if (result.body() == null) {
                logger.warn("[{}] feed fetch failed: {}", feedUrl(), result.failureReason());
                return RefreshOutcome.failed(result.failureReason());
            }
            loadFromBytes(result.body());
            lastETag = result.eTag();
            lastModifiedHeader = result.lastModified();
            loadedSuccessfully = true;
            writeValidators();
            logger.info("[{}] feed refreshed: {} entries", feedUrl(), sortedHashes.length);
            return RefreshOutcome.updated(sortedHashes.length);
        } catch (Exception e) {
            // Defensive catch-all: refresh() must never throw, or it would take the caller
            // (scheduler thread, or an ApplicationReadyEvent listener) down with it.
            logger.warn("[{}] feed refresh failed: {}", feedUrl(), e.getMessage());
            return RefreshOutcome.failed(e.getMessage());
        }
    }

    /**
     * Result of one {@link #refresh()} call.
     *
     * @param status       what happened
     * @param entryCount   entries loaded, only meaningful for {@link Status#UPDATED}
     * @param failureReason why the refresh failed, only set for {@link Status#FAILED}
     */
    public record RefreshOutcome(Status status, int entryCount, String failureReason) {
        public enum Status {
            /** New content downloaded and loaded. */
            UPDATED,
            /** Upstream answered 304 — the copy already held is current. */
            UNCHANGED,
            /** The sync interval floor had not elapsed, so no request was made. */
            SKIPPED,
            /** Download or parse failed; the previously loaded copy keeps serving. */
            FAILED
        }

        static RefreshOutcome updated(int entryCount) {
            return new RefreshOutcome(Status.UPDATED, entryCount, null);
        }

        static RefreshOutcome unchanged() {
            return new RefreshOutcome(Status.UNCHANGED, 0, null);
        }

        static RefreshOutcome skipped() {
            return new RefreshOutcome(Status.SKIPPED, 0, null);
        }

        static RefreshOutcome failed(String reason) {
            return new RefreshOutcome(Status.FAILED, 0, reason);
        }
    }

    private void loadFromBytes(byte[] body) throws IOException {
        loadFromBytes(body, true);
    }

    /**
     * Parses {@code body} into the in-memory hash index, optionally writing it back to disk.
     *
     * @param body    raw feed bytes
     * @param persist {@code true} for a fresh download; {@code false} when re-reading the file
     *                we already have, since rewriting it would reset its modification time and
     *                make a cached feed look newly fetched
     */
    private void loadFromBytes(byte[] body, boolean persist) throws IOException {
        // Accumulate into a growable primitive array, then sort-and-dedupe in place, rather
        // than collecting into a Set<Long> first. At Phishing Army's scale a HashSet<Long>
        // costs ~21 MB transient (boxed Long + HashMap.Node + table slot per entry) to build
        // a ~3 MB array -- which would undercut the whole reason this class stores hashes as
        // primitives in the first place, just moved from steady state into a load-time spike.
        long[] hashes = new long[4096];
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = normalizeLine(line);
                if (normalized != null && !normalized.isEmpty()) {
                    if (count == hashes.length) {
                        hashes = Arrays.copyOf(hashes, hashes.length * 2);
                    }
                    hashes[count++] = Fnv1aHash.hash64(normalized);
                }
            }
        }
        long[] sorted = Arrays.copyOf(hashes, count);
        Arrays.sort(sorted);
        sorted = dedupeSorted(sorted);

        if (persist && rawFeedFile != null) {
            Files.createDirectories(rawFeedFile.toAbsolutePath().getParent());
            Path tmp = rawFeedFile.resolveSibling(rawFeedFile.getFileName() + ".tmp");
            Files.write(tmp, body);
            Files.move(tmp, rawFeedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        // Swap only after both the array and the on-disk file are ready, so a reader never
        // sees a tier-1 hit with no matching tier-2 file to confirm it against.
        this.sortedHashes = sorted;
    }

    /** Collapses duplicates in an already-sorted array, allocating only the trimmed result. */
    private static long[] dedupeSorted(long[] sorted) {
        if (sorted.length == 0) {
            return sorted;
        }
        int unique = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[unique - 1]) {
                sorted[unique++] = sorted[i];
            }
        }
        return unique == sorted.length ? sorted : Arrays.copyOf(sorted, unique);
    }

    /**
     * Loads the previously downloaded feed from disk once per JVM, so a restart reuses what
     * was already fetched instead of pulling the whole feed again.
     *
     * <p>Treats the file's modification time as the last sync attempt, which makes
     * {@link #minSyncIntervalMillis()} survive restarts, and restores the cached ETag /
     * Last-Modified so the next due refresh can still answer 304 instead of re-downloading
     * a body that has not changed.
     */
    private void hydrateFromDisk() {
        if (hydratedFromDisk) {
            return;
        }
        hydratedFromDisk = true;
        if (rawFeedFile == null || !Files.exists(rawFeedFile)) {
            return;
        }
        try {
            long modified = Files.getLastModifiedTime(rawFeedFile).toMillis();
            loadFromBytes(Files.readAllBytes(rawFeedFile), false);
            loadedSuccessfully = true;
            lastSyncAttemptEpochMillis = modified;
            readValidators();
            logger.info("[{}] reusing cached feed: {} entries, {} minutes old",
                    feedUrl(), sortedHashes.length,
                    (System.currentTimeMillis() - modified) / 60_000);
        } catch (Exception e) {
            // A corrupt or unreadable cache must not stop a real refresh from happening.
            logger.warn("[{}] could not reuse cached feed: {}", feedUrl(), e.getMessage());
        }
    }

    /** @return sidecar path holding the cached conditional-GET validators */
    private Path validatorFile() {
        return rawFeedFile == null ? null : rawFeedFile.resolveSibling(rawFeedFile.getFileName() + ".meta");
    }

    private void writeValidators() {
        Path meta = validatorFile();
        if (meta == null) {
            return;
        }
        try {
            Files.writeString(meta,
                    (lastETag == null ? "" : lastETag) + "\n" + (lastModifiedHeader == null ? "" : lastModifiedHeader),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("[{}] could not persist feed validators: {}", feedUrl(), e.getMessage());
        }
    }

    private void readValidators() {
        Path meta = validatorFile();
        if (meta == null || !Files.exists(meta)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                lastETag = lines.get(0);
            }
            if (lines.size() > 1 && !lines.get(1).isBlank()) {
                lastModifiedHeader = lines.get(1);
            }
        } catch (IOException e) {
            logger.debug("[{}] could not read feed validators: {}", feedUrl(), e.getMessage());
        }
    }

    private FetchResult fetch() {
        try {
            return restTemplate.execute(feedUrl(), HttpMethod.GET,
                    request -> {
                        HttpHeaders headers = request.getHeaders();
                        extraHeaders().forEach(headers::add);
                        headers.add(HttpHeaders.ACCEPT, "text/plain, text/csv, */*");
                        if (lastETag != null) {
                            headers.add(HttpHeaders.IF_NONE_MATCH, lastETag);
                        }
                        if (lastModifiedHeader != null) {
                            headers.add(HttpHeaders.IF_MODIFIED_SINCE, lastModifiedHeader);
                        }
                    },
                    response -> {
                        int status = response.getStatusCode().value();
                        if (status == 304) {
                            return FetchResult.ofNotModified();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return FetchResult.failure("HTTP " + status);
                        }
                        byte[] body;
                        try {
                            body = readCapped(response.getBody(), MAX_FEED_BYTES);
                        } catch (FeedTooLargeException e) {
                            return FetchResult.failure(e.getMessage());
                        }
                        String eTag = response.getHeaders().getETag();
                        String lastModified = response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
                        return FetchResult.success(body, eTag, lastModified);
                    });
        } catch (Exception e) {
            return FetchResult.failure(e.getMessage());
        }
    }

    /** Reads at most {@code maxBytes}, throwing if the stream has more — never buffers past the cap. */
    private static byte[] readCapped(InputStream in, long maxBytes) throws IOException, FeedTooLargeException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new FeedTooLargeException("feed exceeds " + maxBytes + " byte cap");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static final class FeedTooLargeException extends Exception {
        FeedTooLargeException(String message) {
            super(message);
        }
    }

    private record FetchResult(boolean notModified, byte[] body, String eTag, String lastModified, String failureReason) {
        static FetchResult ofNotModified() {
            return new FetchResult(true, null, null, null, null);
        }

        static FetchResult success(byte[] body, String eTag, String lastModified) {
            return new FetchResult(false, body, eTag, lastModified, null);
        }

        static FetchResult failure(String reason) {
            return new FetchResult(false, null, null, null, reason);
        }
    }
}
