package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Plain Mockito unit tests for {@link FileDownloadService} -- constructed directly (no
 * Spring context) so every branch of the download/preview/share-stream logic can be
 * exercised quickly, including the atomic-claim share-token race-fix path and the
 * legacy-sidecar fallback that a full integration test would rarely hit.
 */
@ExtendWith(MockitoExtension.class)
class FileDownloadServiceTest {

    @Mock
    private UploadRepository uploadRepository;
    @Mock
    private ApplicationSettingsService applicationSettingsService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private SvgRasterizationService svgRasterizationService;
    @Mock
    private ShareTokenRepository shareTokenRepository;
    @Mock
    private ActivityLogRepository activityLogRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileQueryService fileQueryService;
    @Mock
    private StorageService storageService;
    @Mock
    private StorageHealthService storageHealthService;

    private FileDownloadService newService() {
        return new FileDownloadService(uploadRepository, applicationSettingsService, encryptionService,
                svgRasterizationService, shareTokenRepository, activityLogRepository, notificationService,
                fileQueryService, storageService, storageHealthService);
    }

    private static StoredFile file(String uuid, String name, boolean encrypted) {
        StoredFile file = new StoredFile();
        file.uuid = uuid;
        file.name = name;
        file.size = 4;
        file.encrypted = encrypted;
        return file;
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest();
    }

    // ---------------------------------------------------------------- downloadFile

    @Test
    void downloadFile_storageDown_returns503() {
        when(storageHealthService.isStorageDown()).thenReturn(true);

        ResponseEntity<StreamingResponseBody> response = newService().downloadFile("any-uuid", request());

        assertEquals(503, response.getStatusCode().value());
        verifyNoInteractions(uploadRepository);
    }

    @Test
    void downloadFile_unknownUuid_returns404() {
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("missing")).thenReturn(java.util.Optional.empty());

        ResponseEntity<StreamingResponseBody> response = newService().downloadFile("missing", request());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void downloadFile_softDeleted_returns404() {
        StoredFile deleted = file("u1", "gone.txt", false);
        deleted.deleted = true;
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u1")).thenReturn(java.util.Optional.of(deleted));

        ResponseEntity<StreamingResponseBody> response = newService().downloadFile("u1", request());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void downloadFile_encryptedDecryptFailure_returns500() throws Exception {
        StoredFile encrypted = file("u2", "secret.txt", true);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u2")).thenReturn(java.util.Optional.of(encrypted));
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn("wrong-pw");
        when(storageService.getInputStream("u2")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(encryptionService.getDecryptedInputStream(any(InputStream.class), anyString()))
                .thenThrow(new RuntimeException("bad password"));

        ResponseEntity<StreamingResponseBody> response = newService().downloadFile("u2", request());

        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void downloadFile_plainOpenFailure_returns404() throws Exception {
        StoredFile plain = file("u3", "plain.txt", false);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u3")).thenReturn(java.util.Optional.of(plain));
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn(null);
        when(storageService.getInputStream("u3")).thenThrow(new IOException("disk error"));

        ResponseEntity<StreamingResponseBody> response = newService().downloadFile("u3", request());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void downloadFile_success_streamsBytesSetsHeadersAndLogsHistory() throws Exception {
        StoredFile plain = file("u4", "report.txt", false);
        plain.size = 5;
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u4")).thenReturn(java.util.Optional.of(plain));
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn(null);
        when(storageService.getInputStream("u4")).thenReturn(new ByteArrayInputStream("hello".getBytes()));

        ResponseEntity<StreamingResponseBody> response = newService().downloadFile("u4", request());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment; filename=\"report.txt\"", response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("5", response.getHeaders().getFirst("Content-Length"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertEquals("hello", out.toString());
        verify(activityLogRepository).save(any());
        verify(notificationService).notifyFileAction(eq(plain), eq(org.rostislav.quickdrop.model.EventType.DOWNLOAD));
    }

    // ----------------------------------------------------------------- previewFile

    @Test
    void previewFile_storageDown_returns503() {
        when(storageHealthService.isStorageDown()).thenReturn(true);

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(503, response.getStatusCode().value());
    }

    @Test
    void previewFile_unknownUuid_returns404() {
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("missing")).thenReturn(java.util.Optional.empty());

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("missing", request(), false);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void previewFile_softDeleted_returns404() {
        StoredFile deleted = file("u", "gone.png", false);
        deleted.deleted = true;
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(deleted));

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void previewFile_previewDisabled_returns403() {
        StoredFile img = file("u", "photo.png", false);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(img));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(false);

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void previewFile_unsupportedType_returns415() {
        StoredFile binary = file("u", "archive.bin", false);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(binary));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(415, response.getStatusCode().value());
    }

    @Test
    void previewFile_overSizeLimit_returns428() {
        StoredFile img = file("u", "photo.png", false);
        img.size = 1000;
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(img));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);
        when(applicationSettingsService.getMaxPreviewSizeBytes()).thenReturn(500L);

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(428, response.getStatusCode().value());
    }

    @Test
    void previewFile_overSizeLimit_manualOverride_succeeds() throws Exception {
        StoredFile img = file("u", "photo.png", false);
        img.size = 1000;
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(img));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);
        when(applicationSettingsService.getMaxPreviewSizeBytes()).thenReturn(500L);
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn(null);
        when(storageService.getInputStream("u")).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), true);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void previewFile_decryptFailure_returns403() throws Exception {
        StoredFile encryptedImg = file("u", "photo.png", true);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(encryptedImg));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);
        when(applicationSettingsService.getMaxPreviewSizeBytes()).thenReturn(Long.MAX_VALUE);
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn("pw");
        when(storageService.getInputStream("u")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(encryptionService.getDecryptedInputStream(any(InputStream.class), anyString()))
                .thenThrow(new RuntimeException("bad password"));

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void previewFile_svgRasterization_success() throws Exception {
        StoredFile svg = file("u", "diagram.svg", false);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(svg));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);
        when(applicationSettingsService.getMaxPreviewSizeBytes()).thenReturn(Long.MAX_VALUE);
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn(null);
        when(storageService.getInputStream("u")).thenReturn(new ByteArrayInputStream("<svg/>".getBytes()));
        when(svgRasterizationService.rasterizeToPng(any())).thenReturn(new byte[]{9, 9});

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getFirst("Content-Type"));
        assertEquals("inline; filename=\"diagram.svg.png\"", response.getHeaders().getFirst("Content-Disposition"));
    }

    @Test
    void previewFile_svgRasterization_failure_returns415() throws Exception {
        StoredFile svg = file("u", "evil.svg", false);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(svg));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);
        when(applicationSettingsService.getMaxPreviewSizeBytes()).thenReturn(Long.MAX_VALUE);
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn(null);
        when(storageService.getInputStream("u")).thenReturn(new ByteArrayInputStream("<svg/>".getBytes()));
        when(svgRasterizationService.rasterizeToPng(any())).thenThrow(new IOException("malformed"));

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals(415, response.getStatusCode().value());
    }

    @Test
    void previewFile_success_setsSecurityHeaders() throws Exception {
        StoredFile img = file("u", "photo.png", false);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(uploadRepository.findByUUID("u")).thenReturn(java.util.Optional.of(img));
        when(applicationSettingsService.isPreviewEnabled()).thenReturn(true);
        when(applicationSettingsService.getMaxPreviewSizeBytes()).thenReturn(Long.MAX_VALUE);
        when(fileQueryService.getFilePasswordFromSessionToken(any())).thenReturn(null);
        when(storageService.getInputStream("u")).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        ResponseEntity<StreamingResponseBody> response = newService().previewFile("u", request(), false);

        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeaders().getFirst("X-Frame-Options"));
    }

    // --------------------------------------------------------- streamFileByShareToken

    @Test
    void streamFileByShareToken_storageDown_returnsNull() {
        when(storageHealthService.isStorageDown()).thenReturn(true);

        assertNull(newService().streamFileByShareToken(new ShareTokenEntity(), request()));
        verifyNoInteractions(shareTokenRepository);
    }

    @Test
    void streamFileByShareToken_expiredToken_returnsNull() {
        StoredFile upload = file("u", "f.txt", false);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, LocalDate.now().minusDays(1), null);
        when(storageHealthService.isStorageDown()).thenReturn(false);

        assertNull(newService().streamFileByShareToken(token, request()));
        verifyNoInteractions(shareTokenRepository);
    }

    @Test
    void streamFileByShareToken_limitedDownloadsExhaustedConcurrently_returnsNull() {
        StoredFile upload = file("u", "f.txt", false);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, null, 1);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(shareTokenRepository.decrementDownloadCount(any())).thenReturn(0);

        assertNull(newService().streamFileByShareToken(token, request()));
        verify(shareTokenRepository, never()).deleteByIdTransactional(any());
    }

    @Test
    void streamFileByShareToken_sidecarMissing_deletesTokenReturnsNull() {
        StoredFile upload = file("u5", "f.txt", true);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, null, null);
        token.shareKeyHash = "hash";
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(storageService.exists("u5-share-tok")).thenReturn(false);

        assertNull(newService().streamFileByShareToken(token, request()));
        verify(shareTokenRepository).deleteByIdTransactional(any());
    }

    @Test
    void streamFileByShareToken_noSessionKey_returnsNull() {
        StoredFile upload = file("u6", "f.txt", true);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, null, null);
        token.shareKeyHash = "hash";
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(storageService.exists("u6-share-tok")).thenReturn(true);

        assertNull(newService().streamFileByShareToken(token, request()));
        verify(activityLogRepository, never()).save(any());
    }

    @Test
    void streamFileByShareToken_withKeyAuth_streamsDecryptsAndCleansUp() throws Exception {
        StoredFile upload = file("u7", "f.txt", true);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, null, 1);
        token.shareKeyHash = "hash";
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(shareTokenRepository.decrementDownloadCount(any())).thenReturn(1);
        when(storageService.exists("u7-share-tok")).thenReturn(true);
        when(storageService.getInputStream("u7-share-tok")).thenReturn(new ByteArrayInputStream("shared".getBytes()));
        when(encryptionService.getDecryptedInputStream(any(InputStream.class), eq("share-key")))
                .thenReturn(new ByteArrayInputStream("shared".getBytes()));
        // decrementDownloadCount() is an atomic DB UPDATE that doesn't mutate this in-memory
        // entity, so simulate the post-decrement DB row (0 remaining) via a fresh instance --
        // exhausted, so post-stream cleanup must delete the token.
        ShareTokenEntity refetched = new ShareTokenEntity("tok", upload, null, 0);
        when(shareTokenRepository.findById(any())).thenReturn(java.util.Optional.of(refetched));

        MockHttpServletRequest req = request();
        req.getSession().setAttribute("share-key-tok", "share-key");

        StreamingResponseBody body = newService().streamFileByShareToken(token, req);
        assertNotNull(body);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);

        assertEquals("shared", out.toString());
        verify(activityLogRepository).save(any());
        verify(notificationService).notifyFileAction(eq(upload), eq(org.rostislav.quickdrop.model.EventType.SHARE_DOWNLOAD));
        // numberOfAllowedDownloads is now 0 on the re-fetched entity -> invalid -> cleaned up.
        verify(shareTokenRepository).deleteByIdTransactional(any());
    }

    @Test
    void streamFileByShareToken_legacyDecryptedSidecar_streamsFromLegacyKey() throws Exception {
        StoredFile upload = file("u8", "f.txt", true);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, null, null);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(storageService.exists("u8-decrypted")).thenReturn(true);
        when(storageService.getInputStream("u8-decrypted")).thenReturn(new ByteArrayInputStream("legacy".getBytes()));

        StreamingResponseBody body = newService().streamFileByShareToken(token, request());
        assertNotNull(body);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);

        assertEquals("legacy", out.toString());
        verify(storageService, never()).getInputStream("u8");
    }

    @Test
    void streamFileByShareToken_legacyNoSidecar_streamsFromMainKey() throws Exception {
        StoredFile upload = file("u9", "f.txt", false);
        ShareTokenEntity token = new ShareTokenEntity("tok", upload, null, null);
        when(storageHealthService.isStorageDown()).thenReturn(false);
        when(storageService.exists("u9-decrypted")).thenReturn(false);
        when(storageService.getInputStream("u9")).thenReturn(new ByteArrayInputStream("main".getBytes()));

        StreamingResponseBody body = newService().streamFileByShareToken(token, request());
        assertNotNull(body);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);

        assertEquals("main", out.toString());
    }

    // ------------------------------------------------------------- deleteShareSidecar

    @Test
    void deleteShareSidecar_noHash_isNoOp() {
        ShareTokenEntity token = new ShareTokenEntity("tok", file("u", "f.txt", false), null, null);

        newService().deleteShareSidecar(token);

        verifyNoInteractions(storageService);
    }

    @Test
    void deleteShareSidecar_deletesWhenPresent() {
        ShareTokenEntity token = new ShareTokenEntity("tok", file("u10", "f.txt", true), null, null);
        token.shareKeyHash = "hash";
        when(storageService.delete("u10-share-tok")).thenReturn(true);

        newService().deleteShareSidecar(token);

        verify(storageService).delete("u10-share-tok");
    }

    @Test
    void deleteShareSidecar_logsWarningButDoesNotThrowOnFailure() {
        ShareTokenEntity token = new ShareTokenEntity("tok", file("u11", "f.txt", true), null, null);
        token.shareKeyHash = "hash";
        when(storageService.delete("u11-share-tok")).thenReturn(false);

        assertDoesNotThrow(() -> newService().deleteShareSidecar(token));
    }
}
