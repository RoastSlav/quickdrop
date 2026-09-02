package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.model.NormalizedUrl;

import static org.junit.jupiter.api.Assertions.*;

class UrlNormalizationServiceTest {

    private final UrlNormalizationService service = new UrlNormalizationService();

    @Test
    void schemeLessInputGetsHttpsPrepended() {
        NormalizedUrl result = service.normalize("example.com/page");
        assertEquals("https://example.com/page", result.absoluteForm());
    }

    @Test
    void explicitHttpSchemeIsPreserved() {
        NormalizedUrl result = service.normalize("http://example.com/page");
        assertEquals("http://example.com/page", result.absoluteForm());
    }

    @Test
    void protocolRelativeInputGetsHttpsPrepended() {
        NormalizedUrl result = service.normalize("//example.com/page");
        assertEquals("https://example.com/page", result.absoluteForm());
    }

    @Test
    void wwwIsPreservedInStoredAbsoluteForm() {
        NormalizedUrl result = service.normalize("www.example.com/page");
        assertEquals("https://www.example.com/page", result.absoluteForm(),
                "www must never be stripped from the stored/redirected-to target");
    }

    @Test
    void wwwIsStrippedInDisplayFormOnly() {
        NormalizedUrl result = service.normalize("www.example.com/page");
        assertEquals("example.com/page", result.displayForm());
    }

    @Test
    void schemeIsStrippedInDisplayForm() {
        NormalizedUrl result = service.normalize("https://example.com/page?x=1");
        assertEquals("example.com/page?x=1", result.displayForm());
    }

    @Test
    void blankInputThrows() {
        InvalidUrlException e = assertThrows(InvalidUrlException.class, () -> service.normalize("   "));
        assertNotNull(e.getMessage());
    }

    @Test
    void nullInputThrows() {
        assertThrows(InvalidUrlException.class, () -> service.normalize(null));
    }

    @Test
    void tooLongInputThrows() {
        String longUrl = "example.com/" + "a".repeat(2048);
        assertThrows(InvalidUrlException.class, () -> service.normalize(longUrl));
    }

    @Test
    void hostlessInputThrows() {
        assertThrows(InvalidUrlException.class, () -> service.normalize("https:///no-host"));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(InvalidUrlException.class, () -> service.normalize("https://[not-valid"));
    }

    @Test
    void displayFormFallsBackToInputWhenUnparseable() {
        String weird = "not a url at all";
        assertEquals(weird, service.toDisplayForm(weird));
    }

    @Test
    void schemelessHostWithPortIsNotMistakenForAScheme() {
        // Dots are technically legal in a scheme per RFC 3986, but no real scheme uses one, so this must read as host:port, not an already-schemed URL.
        NormalizedUrl result = service.normalize("example.com:8080/page");
        assertEquals("https://example.com:8080/page", result.absoluteForm());
    }

    @Test
    void opaqueSchemeIsPassedThroughUnmangled() {
        // Opaque schemes are host-less by design; rejecting/mangling them is UrlSafetyValidator's job, not normalize()'s.
        NormalizedUrl result = service.normalize("javascript:alert(1)");
        assertEquals("javascript:alert(1)", result.absoluteForm());
    }
}
