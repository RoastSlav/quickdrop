package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.model.LinkVerdict;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkGuardTest {

    // Left unstubbed on purpose: getShortenerDomainRuleMode() returns null by default from
    // a bare Mockito mock, and LinkGuard treats a null mode the same as "OFF" -- exercising
    // that fallback is as good a check of the domain-rule wiring as stubbing "OFF" explicitly.
    @Mock
    private ApplicationSettingsService applicationSettingsService;

    private LinkGuard linkGuard;

    @BeforeEach
    void setUp() {
        // Constructed here, not as a field initializer -- @Mock fields aren't injected until
        // after the test instance is constructed, so building LinkGuard too early would
        // capture a null ApplicationSettingsService.
        //
        // A real ReputationCheckService with no providers and the same mocked settings service
        // is used rather than a mock of ReputationCheckService itself: isReputationCheckEnabled()
        // defaults to false on a bare Mockito mock, so check() short-circuits to "allowed"
        // without needing an explicit stub -- one less thing for every test here to know about.
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
        // Simulates DNS rebinding: a URL that was safe at creation time now resolves
        // (or was always) to a private address — checkForRedirect must catch it, not
        // just checkForCreation.
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
        // www.example.com is used here (not e.g. sub.evil.com) because UrlSafetyValidator
        // performs a real DNS lookup before the domain-rule check ever runs -- a made-up
        // subdomain wouldn't resolve and would be rejected earlier for the wrong reason
        // (unsafe_destination instead of domain_rejected), leaving the domain-rule stubs
        // unexercised.
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
        // "notevil.com" contains "evil.com" as a suffix of its label, but is a different
        // domain -- domainMatches must compare on a dot boundary, not raw string suffix.
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("evil.com");

        LinkVerdict verdict = linkGuard.checkForCreation("https://notevil.com/page");
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
        // The domain blocklist must apply on every resolve, not just at creation -- an admin
        // can add a domain to the blocklist after links to it already exist.
        when(applicationSettingsService.getShortenerDomainRuleMode()).thenReturn("BLOCKLIST");
        when(applicationSettingsService.getShortenerDomainRules()).thenReturn("evil.com");

        LinkVerdict verdict = linkGuard.checkForRedirect("https://evil.com/page");
        assertFalse(verdict.allowed());
        assertEquals("domain_rejected", verdict.reasonCode());
    }
}
