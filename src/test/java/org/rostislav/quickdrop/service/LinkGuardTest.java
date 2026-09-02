package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.model.LinkVerdict;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkGuardTest {

    // Left unstubbed on purpose: a bare mock returns null from getShortenerDomainRuleMode(), and LinkGuard treats null the same as "OFF".
    @Mock
    private ApplicationSettingsService applicationSettingsService;

    private LinkGuard linkGuard;

    @BeforeEach
    void setUp() {
        // Built here, not as a field initializer, since @Mock fields aren't injected until after construction.
        // A real (provider-less) ReputationCheckService is used instead of a mock of it: isReputationCheckEnabled()
        // defaults to false on a bare mock, so check() short-circuits to "allowed" without an explicit stub.
        linkGuard = new LinkGuard(new UrlNormalizationService(), new UrlSafetyValidator(), applicationSettingsService,
                new ReputationCheckService(java.util.List.of(), applicationSettingsService));
    }

    @Test
    void safePublicUrlIsAllowed() {
        LinkVerdict verdict = linkGuard.checkForCreation("example.com/page");
        assertTrue(verdict.allowed());
        assertEquals("https://example.com/page", verdict.normalizedUrl());
    }

    @Test
    void invalidInputIsBlockedWithInvalidUrlReason() {
        LinkVerdict verdict = linkGuard.checkForCreation("   ");
        assertFalse(verdict.allowed());
        assertEquals("invalid_url", verdict.reasonCode());
        assertNotNull(verdict.userMessage());
    }

    @Test
    void unsafeSchemeIsBlockedWithUnsafeDestinationReason() {
        LinkVerdict verdict = linkGuard.checkForCreation("javascript:alert(1)");
        assertFalse(verdict.allowed());
        assertEquals("unsafe_destination", verdict.reasonCode());
    }

    @Test
    void privateIpIsBlockedAtCreation() {
        LinkVerdict verdict = linkGuard.checkForCreation("http://127.0.0.1/admin");
        assertFalse(verdict.allowed());
    }

    @Test
    void protocolRelativeInputIsNormalizedAndAllowed() {
        LinkVerdict verdict = linkGuard.checkForCreation("//example.com/page");
        assertTrue(verdict.allowed());
        assertEquals("https://example.com/page", verdict.normalizedUrl());
    }

    @Test
    void checkForRedirectAllowsAPreviouslyStoredSafeUrl() {
        LinkVerdict verdict = linkGuard.checkForRedirect("https://example.com/page");
        assertTrue(verdict.allowed());
    }

    @Test
    void checkForRedirectReRejectsAUrlThatHasBecomeUnsafe() {
        // Simulates DNS rebinding: checkForRedirect must catch an unsafe destination too, not just checkForCreation.
        LinkVerdict verdict = linkGuard.checkForRedirect("http://192.168.1.1/");
        assertFalse(verdict.allowed());
        assertEquals("unsafe_destination", verdict.reasonCode());
    }

    @Test
    void checkForRedirectBlocksAMalformedStoredUrl() {
        LinkVerdict verdict = linkGuard.checkForRedirect("not a url");
        assertFalse(verdict.allowed());
        assertEquals("invalid_url", verdict.reasonCode());
    }

    // --------------------------------------------------------- domain rules

    @Test
    void blocklistRejectsAListedDomain() {
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("evil.com");

        LinkVerdict verdict = linkGuard.checkForCreation("https://evil.com/page");
        assertFalse(verdict.allowed());
        assertEquals("domain_rejected", verdict.reasonCode());
    }

    @Test
    void blocklistRejectsASubdomainOfAListedDomain() {
        // www.example.com (a real, resolvable domain) is used because UrlSafetyValidator's DNS lookup runs before the domain-rule check; a made-up subdomain would fail for unsafe_destination first, leaving the domain-rule stubs unexercised.
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("example.com");

        LinkVerdict verdict = linkGuard.checkForCreation("https://www.example.com/page");
        assertFalse(verdict.allowed());
        assertEquals("domain_rejected", verdict.reasonCode());
    }

    @Test
    void blocklistAllowsAnUnlistedDomain() {
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("evil.com");

        LinkVerdict verdict = linkGuard.checkForCreation("https://example.com/page");
        assertTrue(verdict.allowed());
    }

    @Test
    void blocklistDoesNotFalsePositiveOnASuffixThatIsNotADomainBoundary() {
        // domainMatches must compare on a dot boundary, not raw string suffix. "notevil.com" isn't a registered domain, so a mocked always-safe validator isolates this from the real DNS lookup.
        UrlSafetyValidator alwaysSafe = mock(UrlSafetyValidator.class);
        when(alwaysSafe.validate(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Optional.empty());
        LinkGuard guardWithoutDnsCheck = new LinkGuard(new UrlNormalizationService(), alwaysSafe,
                applicationSettingsService, new ReputationCheckService(java.util.List.of(), applicationSettingsService));

        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("evil.com");

        LinkVerdict verdict = guardWithoutDnsCheck.checkForCreation("https://notevil.com/page");
        assertTrue(verdict.allowed());
    }

    @Test
    void allowlistRejectsADomainNotOnTheList() {
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("ALLOWLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("example.com");

        LinkVerdict verdict = linkGuard.checkForCreation("https://other.com/page");
        assertFalse(verdict.allowed());
        assertEquals("domain_rejected", verdict.reasonCode());
    }

    @Test
    void allowlistAllowsADomainOnTheList() {
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("ALLOWLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("example.com");

        LinkVerdict verdict = linkGuard.checkForCreation("https://example.com/page");
        assertTrue(verdict.allowed());
    }

    @Test
    void domainRuleModeOffIgnoresAConfiguredList() {
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("OFF");

        LinkVerdict verdict = linkGuard.checkForCreation("https://example.com/page");
        assertTrue(verdict.allowed());
    }

    @Test
    void domainRulesAreCaseInsensitiveAndIgnoreBlankLines() {
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("\nEVIL.com\n\n");

        LinkVerdict verdict = linkGuard.checkForCreation("https://evil.com/page");
        assertFalse(verdict.allowed());
    }

    @Test
    void checkForRedirectAlsoAppliesDomainRules() {
        // The domain blocklist must apply on every resolve, not just creation -- an admin can blocklist a domain after links to it already exist.
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("evil.com");

        LinkVerdict verdict = linkGuard.checkForRedirect("https://evil.com/page");
        assertFalse(verdict.allowed());
        assertEquals("domain_rejected", verdict.reasonCode());
    }
}
