package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Exercises the real parse/hash/tier-1/tier-2 pipeline against known feed content via
 * {@link AbstractHashFeedProvider#loadForTest} -- never hits the live Phishing Army feed.
 */
@ExtendWith(MockitoExtension.class)
class PhishingArmyProviderTest {

    @Mock
    private ApplicationSettingsService applicationSettingsService;

    private PhishingArmyProvider provider;
    private Path feedFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        feedFile = tempDir.resolve("phishing_army.txt");
        provider = new PhishingArmyProvider(new RestTemplateBuilder(), applicationSettingsService, feedFile);
    }

    private void load(String feedContent) throws IOException {
        provider.loadForTest(feedContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isMaliciousThrowsWhenFeedNeverLoaded() {
        assertThrows(ReputationCheckException.class, () -> provider.isMalicious(URI.create("https://evil.com/x")));
    }

    @Test
    void blocksAnExactListedDomain() throws Exception {
        load("evil.com\n");
        assertTrue(provider.isMalicious(URI.create("https://evil.com/phish")));
    }

    @Test
    void blocksASubdomainOfAListedDomain() throws Exception {
        load("evil.com\n");
        assertTrue(provider.isMalicious(URI.create("https://a.b.evil.com/phish")));
    }

    @Test
    void allowsAnUnlistedDomain() throws Exception {
        load("evil.com\n");
        assertFalse(provider.isMalicious(URI.create("https://example.com/page")));
    }

    @Test
    void doesNotFalsePositiveOnASuffixThatIsNotADomainBoundary() throws Exception {
        load("evil.com\n");
        assertFalse(provider.isMalicious(URI.create("https://notevil.com/page")));
    }

    @Test
    void skipsCommentsAndBlankLines() throws Exception {
        load("# comment\n\nevil.com\n   \n");
        assertTrue(provider.isMalicious(URI.create("https://evil.com/x")));
        assertFalse(provider.isMalicious(URI.create("https://comment.example/x")));
    }

    @Test
    void matchingIsCaseInsensitive() throws Exception {
        load("Evil.COM\n");
        assertTrue(provider.isMalicious(URI.create("https://EVIL.com/x")));
    }

    @Test
    void duplicateEntriesInTheFeedAreCollapsedAndStillMatch() throws Exception {
        // Covers the sort-then-dedupe-in-place path in loadFromBytes: a feed listing the same
        // domain repeatedly must still match, and must not leave duplicate hashes behind.
        load("evil.com\nevil.com\nother.com\nevil.com\n");

        assertTrue(provider.isMalicious(URI.create("https://evil.com/x")));
        assertTrue(provider.isMalicious(URI.create("https://other.com/x")));
        assertFalse(provider.isMalicious(URI.create("https://clean.com/x")));
    }

    @Test
    void anEmptyFeedLoadsCleanlyAndMatchesNothing() throws Exception {
        load("# only a comment\n\n");

        assertTrue(provider.isLoaded());
        assertFalse(provider.isMalicious(URI.create("https://evil.com/x")));
    }

    @Test
    void isLoadedReflectsWhetherAFeedHasEverSucceeded() throws Exception {
        assertFalse(provider.isLoaded());
        load("evil.com\n");
        assertTrue(provider.isLoaded());
    }

    @Test
    void suffixCandidatesWalksLabelsAndStopsBeforeTheBareTld() {
        assertEquals(List.of("a.b.evil.com", "b.evil.com", "evil.com"),
                PhishingArmyProvider.suffixCandidates("a.b.evil.com"));
        assertEquals(List.of("evil.com"), PhishingArmyProvider.suffixCandidates("evil.com"));
    }

    @Test
    void idAndEnabledReflectSettings() {
        assertEquals("phishing_army", provider.id());
        when(applicationSettingsService.isReputationPhishingArmyEnabled()).thenReturn(true);
        assertTrue(provider.isEnabled());
    }

    /**
     * A restart used to re-download the whole feed: the last-sync timestamp and the cached
     * ETag / Last-Modified all lived only in memory, so the min-sync-interval floor never
     * applied on boot and no conditional-GET validators were sent. refresh() now seeds itself
     * from the copy already on disk.
     *
     * <p>The feed file is written with a current timestamp, so refresh() must treat it as
     * fresh and make no network call at all — if it tried, this test would fail on the
     * unreachable host rather than pass.
     */
    @Test
    void restartReusesCachedFeedInsteadOfDownloadingAgain() throws Exception {
        Files.writeString(feedFile, "evil.com" + System.lineSeparator(), StandardCharsets.UTF_8);
        long mtimeBefore = Files.getLastModifiedTime(feedFile).toMillis();

        // a second provider over the same path stands in for a fresh JVM after restart
        PhishingArmyProvider afterRestart =
                new PhishingArmyProvider(new RestTemplateBuilder(), applicationSettingsService, feedFile);
        assertFalse(afterRestart.isLoaded(), "provider should start cold");

        afterRestart.refresh();

        assertTrue(afterRestart.isLoaded(), "cached feed should be loaded from disk");
        assertTrue(afterRestart.isMalicious(URI.create("https://evil.com/phish")),
                "entries from the cached feed should be matchable");
        assertEquals(mtimeBefore, Files.getLastModifiedTime(feedFile).toMillis(),
                "hydrating must not rewrite the file, which would reset its age");
    }
}
