package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.UploadShareLink;
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

    // formatFileSize must not use the JVM default locale's decimal separator (European JVMs printed "5,00 GB").

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

        // X-Forwarded-Host is used verbatim; the proxy is expected to supply the externally-visible host:port itself.
        assertEquals("http://public.example.com/file/abc-123", link);
    }

    @Test
    void getDownloadLinkHonoursXForwardedProtoOverRequestScheme() {
        // Proxy terminates TLS on 443 and forwards to the backend's own standard-for-https port, so no port suffix is expected.
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
        // Regression guard: when only X-Forwarded-Proto is set, getServerPort() is the backend's raw listening port, not the client-facing one -- pairing them used to leak the internal port.
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
        // X-Forwarded-Port is an authoritative signal for the client-facing port, so it's used verbatim rather than the standard-port fallback.
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
    void validateShareTokenNullIsInvalid() {
        assertFalse(FileUtils.validateShareToken(null));
    }

    @Test
    void validateShareTokenNoConstraintsIsValid() {
        UploadShareLink token = new UploadShareLink("abcde", fileWithUuid("u1"), null, null);
        assertTrue(FileUtils.validateShareToken(token));
    }

    @Test
    void validateShareTokenExpiredTodayIsInvalid() {
        UploadShareLink token = new UploadShareLink("abcde", fileWithUuid("u1"), LocalDate.now(), null);
        assertFalse(FileUtils.validateShareToken(token), "a token expiring today should be considered expired");
    }

    @Test
    void validateShareTokenFutureExpiryIsValid() {
        UploadShareLink token = new UploadShareLink("abcde", fileWithUuid("u1"), LocalDate.now().plusDays(1), null);
        assertTrue(FileUtils.validateShareToken(token));
    }

    @Test
    void validateShareTokenExhaustedDownloadsIsInvalid() {
        UploadShareLink token = new UploadShareLink("abcde", fileWithUuid("u1"), null, 0);
        assertFalse(FileUtils.validateShareToken(token));
    }

    @Test
    void validateShareTokenRemainingDownloadsIsValid() {
        UploadShareLink token = new UploadShareLink("abcde", fileWithUuid("u1"), null, 3);
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
