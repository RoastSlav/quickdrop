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
 * track's assigned scope — see docs/AGENTIC_TEST_PLAN.md Track A.
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
    void getDownloadLinkFindingLeaksBackendPortWhenOnlyProtoIsForwardedWithoutHost() {
        // FINDING (minor): when a proxy forwards only X-Forwarded-Proto (not
        // X-Forwarded-Host) -- e.g. a minimal proxy config, or the backend Tomcat port
        // differing from the standard port for the forwarded scheme -- getDownloadLink()
        // compares the *forwarded* scheme against the *raw* request.getServerPort()
        // (the backend's own listening port, e.g. 8080), not the port the client
        // actually connected to. That means a client on https://public-host/ can be
        // handed back a link like "https://internal-host:8080/file/..." which is wrong
        // (leaks the internal port, wrong scheme/port pairing) whenever the proxy
        // doesn't also forward the Host header. This test documents the CURRENT
        // behaviour rather than asserting the (arguably more correct) alternative,
        // since fixing it is a production-code change out of this test track's scope.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("internal-host");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Proto", "https");
        Upload upload = fileWithUuid("abc-123");

        String link = FileUtils.getDownloadLink(request, upload);

        assertEquals("https://internal-host:8080/file/abc-123", link);
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
