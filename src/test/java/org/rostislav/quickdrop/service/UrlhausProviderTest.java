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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Exercises the real CSV-parse/hash/tier-1/tier-2 pipeline against known feed content via
 * {@link AbstractHashFeedProvider#loadForTest} -- never hits the live URLhaus feed.
 */
@ExtendWith(MockitoExtension.class)
class UrlhausProviderTest {

    private static final String SAMPLE_ROW =
            "\"12345\",\"2024-01-01 00:00:00\",\"http://evil.example/malware.exe\",\"online\",\"\",\"malware_download\",\"\",\"https://urlhaus.abuse.ch/url/12345/\",\"reporter\"";

    @Mock
    private ApplicationSettingsService applicationSettingsService;

    private UrlhausProvider provider;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        provider = new UrlhausProvider(new RestTemplateBuilder(), applicationSettingsService, tempDir.resolve("urlhaus.csv"));
    }

    private void load(String feedContent) throws IOException {
        provider.loadForTest(feedContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isMaliciousThrowsWhenFeedNeverLoaded() {
        assertThrows(ReputationCheckException.class, () -> provider.isMalicious(URI.create("http://evil.example/malware.exe")));
    }

    @Test
    void blocksAnExactListedUrl() throws Exception {
        load("# comment header\n" + SAMPLE_ROW + "\n");
        assertTrue(provider.isMalicious(URI.create("http://evil.example/malware.exe")));
    }

    @Test
    void allowsADifferentPathOnTheSameHost() throws Exception {
        // Deliberately not host matching: URLhaus entries frequently sit on compromised legitimate hosts.
        load(SAMPLE_ROW + "\n");
        assertFalse(provider.isMalicious(URI.create("http://evil.example/some-other-page")));
    }

    @Test
    void matchIsCaseInsensitiveAndIgnoresATrailingSlash() throws Exception {
        load(SAMPLE_ROW + "\n");
        assertTrue(provider.isMalicious(URI.create("HTTP://EVIL.EXAMPLE/malware.exe")));
        assertTrue(provider.isMalicious(URI.create("http://evil.example/malware.exe/")));
    }

    @Test
    void skipsCommentAndMalformedLines() throws Exception {
        load("# id,dateadded,url,...\nnot,enough,columns\n" + SAMPLE_ROW + "\n");
        assertTrue(provider.isMalicious(URI.create("http://evil.example/malware.exe")));
    }

    @Test
    void isEnabledRequiresBothTheFlagAndAnAuthKey() {
        when(applicationSettingsService.isReputationUrlhausEnabled()).thenReturn(true);
        when(applicationSettingsService.getUrlhausAuthKey()).thenReturn("");
        assertFalse(provider.isEnabled(), "blank auth key must disable the provider even if the flag is on");

        when(applicationSettingsService.getUrlhausAuthKey()).thenReturn("real-key");
        assertTrue(provider.isEnabled());
    }

    @Test
    void id() {
        assertEquals("urlhaus", provider.id());
    }
}
