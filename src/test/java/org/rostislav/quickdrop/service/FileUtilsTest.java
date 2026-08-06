package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.util.FileUtils;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for {@link FileUtils}, the plain-static-utility class shared by
 * controllers and services. No Spring context needed.
 *
 * <p>Placed under service/ (rather than a mirrored util/ test directory) per this
 * track's assigned scope — see docs/TESTING.md.
 */
class FileUtilsTest {

    private Locale originalDefaultLocale;

    @BeforeEach
    void captureLocale() {
        originalDefaultLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalDefaultLocale);
    }

    // -------------------------------------------------------------------------
    // formatFileSize — locale regression (European JVMs printed "5,00 GB")
    // -------------------------------------------------------------------------

    @Test
    void formatFileSizeUsesDotDecimalUnderGermanDefaultLocale() {
        Locale.setDefault(Locale.GERMANY);
        String formatted = FileUtils.formatFileSize(5L * 1024 * 1024 * 1024);
        assertEquals("5.00 GB", formatted, "formatFileSize must not use the JVM default locale's decimal separator");
        assertFalse(formatted.contains(","), "must not contain a comma decimal separator under Locale.GERMANY");
    }

    @Test
    void formatFileSizeUsesDotDecimalUnderFrenchDefaultLocale() {
        Locale.setDefault(Locale.FRANCE);
        String formatted = FileUtils.formatFileSize(1536); // 1.5 KB
        assertEquals("1.50 KB", formatted);
    }

    @Test
    void formatFileSizeBasicUnitBoundaries() {
        Locale.setDefault(Locale.ROOT);
        assertEquals("0.00 B", FileUtils.formatFileSize(0));
        assertEquals("512.00 B", FileUtils.formatFileSize(512));
        assertEquals("1.00 KB", FileUtils.formatFileSize(1024));
        assertEquals("1.00 MB", FileUtils.formatFileSize(1024L * 1024));
        assertEquals("1.00 GB", FileUtils.formatFileSize(1024L * 1024 * 1024));
        assertEquals("1.00 TB", FileUtils.formatFileSize(1024L * 1024 * 1024 * 1024));
    }

    // -------------------------------------------------------------------------
    // getDownloadLink — port + X-Forwarded-Host handling
    // -------------------------------------------------------------------------

    @Test
    void getDownloadLinkIncludesNonDefaultPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("example.com");
        request.setServerPort(8080);
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("http://example.com:8080/file/abc-123", link);
    }

    @Test
    void getDownloadLinkOmitsDefaultHttpPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("example.com");
        request.setServerPort(80);
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("http://example.com/file/abc-123", link);
    }

    @Test
    void getDownloadLinkOmitsDefaultHttpsPort() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("example.com");
        request.setServerPort(443);
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("https://example.com/file/abc-123", link);
    }

    @Test
    void getDownloadLinkHonoursXForwardedHostOverServerName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Host", "public.example.com");
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        // X-Forwarded-Host is used verbatim (no port appended) since the proxy is
        // expected to supply the externally-visible host:port itself if needed.
        assertEquals("http://public.example.com/file/abc-123", link);
    }

    @Test
    void getDownloadLinkHonoursXForwardedProtoOverRequestScheme() {
        // Realistic reverse-proxy setup: the proxy terminates TLS on 443 and forwards
        // to the backend on its own (already-standard-for-https) port, so no port
        // suffix is expected in the generated link.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("example.com");
        request.setServerPort(443);
        request.addHeader("X-Forwarded-Proto", "https");
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("https://example.com/file/abc-123", link);
    }

    @Test
    void getDownloadLinkAssumesSchemeDefaultPortWhenOnlyProtoIsForwarded() {
        // Regression guard for a fixed port-leak bug: when a proxy forwards only
        // X-Forwarded-Proto (not Host or Port) -- e.g. a minimal proxy config --
        // request.getServerPort() is the backend's own raw listening port (8080 here),
        // not the port the client actually connected to. Pairing that raw port with the
        // forwarded scheme used to produce "https://internal-host:8080/...", leaking the
        // internal port. getDownloadLink() now assumes the scheme's standard port in this
        // case instead, since that holds for effectively every reverse-proxy deployment.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("https://internal-host/file/abc-123", link);
    }

    @Test
    void getDownloadLinkHonoursXForwardedPortWhenProvided() {
        // A proxy that forwards its own non-standard external port explicitly (rather
        // than relying on the "assume standard port" fallback above) should have that
        // port used verbatim, since it's an authoritative signal for the client-facing port.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "9443");
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("https://internal-host:9443/file/abc-123", link);
    }

    @Test
    void getDownloadLinkOmitsForwardedPortWhenItMatchesSchemeDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("https://internal-host/file/abc-123", link);
    }

    // -------------------------------------------------------------------------
    // Misc small helpers
    // -------------------------------------------------------------------------

    @Test
    void clampPageNeverGoesNegative() {
        assertEquals(0, FileUtils.clampPage(-5));
        assertEquals(0, FileUtils.clampPage(0));
        assertEquals(7, FileUtils.clampPage(7));
    }

    @Test
    void clampSizeStaysWithinBounds() {
        assertEquals(1, FileUtils.clampSize(0));
        assertEquals(1, FileUtils.clampSize(-10));
        assertEquals(100, FileUtils.clampSize(500));
        assertEquals(42, FileUtils.clampSize(42));
    }

    @Test
    void generateHashedTokenIsFiveBase62Characters() {
        Upload upload = fileWithUuid(java.util.UUID.randomUUID().toString());
        upload.size = 12345;
        upload.uploadDate = LocalDate.now();

        String token = FileUtils.generateHashedToken(upload);

        assertEquals(5, token.length());
        assertTrue(token.matches("[0-9A-Za-z]{5}"), "token should be base-62: " + token);
    }

    @Test
    void generateHashedTokenIsNotConstantAcrossCalls() {
        Upload upload = fileWithUuid(java.util.UUID.randomUUID().toString());
        upload.size = 100;
        upload.uploadDate = LocalDate.now();

        String first = FileUtils.generateHashedToken(upload);
        String second = FileUtils.generateHashedToken(upload);

        assertNotEquals(first, second, "token generation includes randomness/nanoTime and should not repeat");
    }

    @Test
    void validateShareTokenNullIsInvalid() {
        assertFalse(FileUtils.validateShareToken(null));
    }

    @Test
    void validateShareTokenNoConstraintsIsValid() {
        ShareTokenEntity token = new ShareTokenEntity("abcde", fileWithUuid("u1"), null, null);
        assertTrue(FileUtils.validateShareToken(token));
    }

    @Test
    void validateShareTokenExpiredTodayIsInvalid() {
        ShareTokenEntity token = new ShareTokenEntity("abcde", fileWithUuid("u1"), LocalDate.now(), null);
        assertFalse(FileUtils.validateShareToken(token), "a token expiring today should be considered expired");
    }

    @Test
    void validateShareTokenFutureExpiryIsValid() {
        ShareTokenEntity token = new ShareTokenEntity("abcde", fileWithUuid("u1"), LocalDate.now().plusDays(1), null);
        assertTrue(FileUtils.validateShareToken(token));
    }

    @Test
    void validateShareTokenExhaustedDownloadsIsInvalid() {
        ShareTokenEntity token = new ShareTokenEntity("abcde", fileWithUuid("u1"), null, 0);
        assertFalse(FileUtils.validateShareToken(token));
    }

    @Test
    void validateShareTokenRemainingDownloadsIsValid() {
        ShareTokenEntity token = new ShareTokenEntity("abcde", fileWithUuid("u1"), null, 3);
        assertTrue(FileUtils.validateShareToken(token));
    }

    @Test
    void previewableExtensionDetection() {
        assertTrue(FileUtils.isPreviewableText(fileWithName("notes.md")));
        assertTrue(FileUtils.isPreviewableImage(fileWithName("photo.PNG")));
        assertTrue(FileUtils.isPreviewablePdf(fileWithName("doc.pdf")));
        assertTrue(FileUtils.isPreviewableJson(fileWithName("data.json")));
        assertTrue(FileUtils.isPreviewableCsvOrTsv(fileWithName("table.csv")));
        assertFalse(FileUtils.isPreviewableText(fileWithName("archive.zip")));
    }

    private static Upload fileWithUuid(String uuid) {
        StoredFile file = new StoredFile();
        file.uuid = uuid;
        file.name = "test.txt";
        return file;
    }

    private static Upload fileWithName(String name) {
        StoredFile file = new StoredFile();
        file.uuid = "u";
        file.name = name;
        return file;
    }
}
