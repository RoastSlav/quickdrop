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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Exercises the real parse/hash/tier-1/tier-2 pipeline against known feed content via
 * {@link AbstractHashFeedProvider#loadForTest} -- never hits the live NetAPI feed.
 */
@ExtendWith(MockitoExtension.class)
class NetApiProviderTest {

    @Mock
    private ApplicationSettingsService applicationSettingsService;

    private NetApiProvider provider;
    private Path feedFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        feedFile = tempDir.resolve("netapi.txt");
        provider = new NetApiProvider(new RestTemplateBuilder(), applicationSettingsService, feedFile);
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
        assertTrue(provider.isMalicious(URI.create("https://evil.com/login")));
    }

    @Test
    void blocksASubdomainOfAListedDomain() throws Exception {
        load("evil.com\n");
        assertTrue(provider.isMalicious(URI.create("https://www.evil.com/login")));
        assertTrue(provider.isMalicious(URI.create("https://a.b.evil.com/login")));
    }

    @Test
    void blocksAListedSubdomainButNotItsParent() throws Exception {
        load("login.evil-host.net\n");
        assertTrue(provider.isMalicious(URI.create("https://login.evil-host.net/")));
        assertFalse(provider.isMalicious(URI.create("https://evil-host.net/")));
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
    void readsTheFeedFormatAsPublished() throws Exception {
        // One hostname per line, no header or comment lines; CRLF and blank lines must not break parsing.
        load("evil.com\r\nphish.example.org\r\n\r\nxn--80ak6aa92e.com\r\n");

        assertTrue(provider.isMalicious(URI.create("https://evil.com/")));
        assertTrue(provider.isMalicious(URI.create("https://phish.example.org/")));
        assertTrue(provider.isMalicious(URI.create("https://xn--80ak6aa92e.com/")));
        assertFalse(provider.isMalicious(URI.create("https://example.org/")));
    }

    @Test
    void matchingIsCaseInsensitive() throws Exception {
        load("Evil.COM\n");
        assertTrue(provider.isMalicious(URI.create("https://EVIL.com/x")));
    }

    @Test
    void isLoadedReflectsWhetherAFeedHasEverSucceeded() throws Exception {
        assertFalse(provider.isLoaded());
        load("evil.com\n");
        assertTrue(provider.isLoaded());
    }

    @Test
    void idAndEnabledReflectSettings() {
        assertEquals("netapi", provider.id());
        when(applicationSettingsService.isReputationNetApiEnabled()).thenReturn(true);
        assertTrue(provider.isEnabled());
    }

    /**
     * The feed file is written with a current timestamp, so refresh() must treat it as fresh
     * and make no network call at all -- if it tried, this test would fail on the unreachable
     * host rather than pass.
     */
    @Test
    void restartReusesCachedFeedInsteadOfDownloadingAgain() throws Exception {
        Files.writeString(feedFile, "evil.com" + System.lineSeparator(), StandardCharsets.UTF_8);
        long mtimeBefore = Files.getLastModifiedTime(feedFile).toMillis();

        NetApiProvider afterRestart = new NetApiProvider(new RestTemplateBuilder(), applicationSettingsService, feedFile);
        assertFalse(afterRestart.isLoaded(), "provider should start cold");

        afterRestart.refresh();

        assertTrue(afterRestart.isLoaded(), "cached feed should be loaded from disk");
        assertTrue(afterRestart.isMalicious(URI.create("https://evil.com/login")),
                "entries from the cached feed should be matchable");
        assertEquals(mtimeBefore, Files.getLastModifiedTime(feedFile).toMillis(),
                "hydrating must not rewrite the file, which would reset its age");
    }
}
