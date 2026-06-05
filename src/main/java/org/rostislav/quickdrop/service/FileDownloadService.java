package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.rostislav.quickdrop.util.FileUtils.*;

/**
 * Streams file content to the browser — direct downloads, in-browser previews, and
 * share-token downloads.
 *
 * <p>Handles both plaintext and AES-encrypted files, SVG rasterization for preview,
 * and share-token lifecycle (download counter decrement, expiry, sidecar cleanup).
 */
@Service
public class FileDownloadService {
    private static final Logger logger = LoggerFactory.getLogger(FileDownloadService.class);

    private final UploadRepository uploadRepository;
    private final ApplicationSettingsService applicationSettingsService;
    private final EncryptionService encryptionService;
    private final SvgRasterizationService svgRasterizationService;
    private final ShareTokenRepository shareTokenRepository;
    private final ActivityLogRepository activityLogRepository;
    private final NotificationService notificationService;
    private final FileQueryService fileQueryService;
    private final StorageService storageService;
    private final StorageHealthService storageHealthService;

    public FileDownloadService(UploadRepository uploadRepository,
                               ApplicationSettingsService applicationSettingsService,
                               EncryptionService encryptionService,
                               SvgRasterizationService svgRasterizationService,
                               ShareTokenRepository shareTokenRepository,
                               ActivityLogRepository activityLogRepository,
                               NotificationService notificationService,
                               FileQueryService fileQueryService,
                               StorageService storageService,
                               StorageHealthService storageHealthService) {
        this.uploadRepository = uploadRepository;
        this.applicationSettingsService = applicationSettingsService;
        this.encryptionService = encryptionService;
        this.svgRasterizationService = svgRasterizationService;
        this.shareTokenRepository = shareTokenRepository;
        this.activityLogRepository = activityLogRepository;
        this.notificationService = notificationService;
        this.fileQueryService = fileQueryService;
        this.storageService = storageService;
        this.storageHealthService = storageHealthService;
    }

    /**
     * Streams the file identified by {@code uuid} as a browser download attachment.
     *
     * <p>For encrypted files the password is retrieved from the caller's file-session token.
     * Returns 404 for unknown or soft-deleted files; 500 if decryption fails.
     */
    public ResponseEntity<StreamingResponseBody> downloadFile(String uuid, HttpServletRequest request) {
        if (storageHealthService.isStorageDown()) {
            logger.info("Download rejected: storage backend is unreachable (file={})", uuid);
            return ResponseEntity.status(503).build();
        }
        Upload fileEntity = uploadRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
            logger.info("File not found: {}", uuid);
            return ResponseEntity.notFound().build();
        }
        if (fileEntity.deleted) {
            logger.info("Download attempted for soft-deleted file: {}", uuid);
            return ResponseEntity.notFound().build();
        }

        String password = fileQueryService.getFilePasswordFromSessionToken(request);

        InputStream inputStream;
        try {
            InputStream raw = storageService.getInputStream(fileEntity.uuid);
            if (fileEntity.encrypted) {
                inputStream = encryptionService.getDecryptedInputStream(raw, password);
            } else {
                inputStream = raw;
            }
        } catch (Exception e) {
            logger.error("Error opening file {}: {}", fileEntity.uuid, e.getMessage());
            return ResponseEntity.status(fileEntity.encrypted
                    ? HttpStatus.INTERNAL_SERVER_ERROR
                    : HttpStatus.NOT_FOUND).build();
        }

        return createFileDownloadResponse(inputStream, fileEntity, request);
    }

    /**
     * Streams the file identified by {@code uuid} for in-browser preview.
     *
     * <p>SVG files are rasterized to PNG before streaming. Returns 428 (Precondition Required)
     * when the file exceeds the configured preview size limit unless {@code manualOverride}
     * is {@code true}. Strict security headers are applied to prevent the browser from
     * executing embedded scripts in the streamed content.
     *
     * @param manualOverride when {@code true}, bypasses the file-size preview limit
     */
    public ResponseEntity<StreamingResponseBody> previewFile(String uuid, HttpServletRequest request, boolean manualOverride) {
        if (storageHealthService.isStorageDown()) {
            return ResponseEntity.status(503).build();
        }
        Upload fileEntity = uploadRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
            return ResponseEntity.notFound().build();
        }
        if (fileEntity.deleted) {
            return ResponseEntity.notFound().build();
        }

        if (!applicationSettingsService.isPreviewEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        boolean isImage = isPreviewableImage(fileEntity);
        boolean isText = isPreviewableText(fileEntity);
        boolean isPdf = isPreviewablePdf(fileEntity);
        if (!isImage && !isText && !isPdf) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        if (fileEntity.size > applicationSettingsService.getMaxPreviewSizeBytes() && !manualOverride) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        }

        String password = fileQueryService.getFilePasswordFromSessionToken(request);

        InputStream inputStream;
        try {
            InputStream raw = storageService.getInputStream(fileEntity.uuid);
            if (fileEntity.encrypted) {
                inputStream = encryptionService.getDecryptedInputStream(raw, password);
            } else {
                inputStream = raw;
            }
        } catch (Exception e) {
            logger.error("Error preparing preview for file {}: {}", uuid, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String contentType = guessContentType(fileEntity.name, isImage, isText, isPdf);
        String previewFileName = fileEntity.name;

        if (isSvgFile(fileEntity.name)) {
            try {
                byte[] pngPreview = svgRasterizationService.rasterizeToPng(inputStream);
                inputStream = new ByteArrayInputStream(pngPreview);
                contentType = "image/png";
                previewFileName = fileEntity.name + ".png";
            } catch (IOException e) {
                logger.warn("Failed to rasterize SVG preview for file {}: {}", uuid, e.getMessage());
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
            }
        }

        StreamingResponseBody body = getStreamingResponseBody(inputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + previewFileName + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'none'; object-src 'none'; frame-ancestors 'none'; sandbox")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }

    /**
     * Returns a {@link StreamingResponseBody} that streams the file for the given share token,
     * then decrements its download counter (or deletes it when exhausted).
     *
     * <p>For password-encrypted shares the re-encrypted sidecar is read using the per-share
     * key from the HTTP session. If the sidecar is missing, the broken token is deleted and
     * {@code null} is returned. Evicts {@code adminFiles} and {@code analytics} caches so
     * dashboards reflect the updated download count.
     *
     * @return the streaming body, or {@code null} if the token is invalid or the sidecar is missing
     */
    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public StreamingResponseBody streamFileByShareToken(ShareTokenEntity shareTokenEntity, HttpServletRequest request) {
        if (storageHealthService.isStorageDown()) {
            logger.info("Share download rejected: storage backend is unreachable");
            return null;
        }
        if (!validateShareToken(shareTokenEntity)) {
            return null;
        }

        Upload upload = shareTokenEntity.file;

        if (shareTokenEntity.shareKeyHash != null) {
            String sidecarKey = upload.uuid + "-share-" + shareTokenEntity.shareToken;
            if (!storageService.exists(sidecarKey)) {
                logger.warn("Sidecar missing for token {}, deleting broken token", shareTokenEntity.shareToken);
                shareTokenRepository.deleteByIdTransactional(shareTokenEntity.getId());
                return null;
            }
            logHistory(upload, request, EventType.SHARE_DOWNLOAD);
            String shareKey = (String) request.getSession().getAttribute("share-key-" + shareTokenEntity.shareToken);
            return outputStream -> {
                try {
                    InputStream decIn;
                    try {
                        InputStream raw = storageService.getInputStream(sidecarKey);
                        decIn = encryptionService.getDecryptedInputStream(raw, shareKey);
                    } catch (Exception e) {
                        throw new IOException("Failed to decrypt share sidecar", e);
                    }
                    try (decIn) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = decIn.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                        outputStream.flush();
                    }
                } finally {
                    updateShareTokenAfterDownload(shareTokenEntity, upload);
                }
            };
        } else {
            logHistory(upload, request, EventType.SHARE_DOWNLOAD);
            // Legacy "-decrypted" sidecar (local storage only); fall back to the main file
            String legacyKey = upload.uuid + "-decrypted";
            String streamKey = storageService.exists(legacyKey) ? legacyKey : upload.uuid;
            return outputStream -> {
                try (InputStream in = storageService.getInputStream(streamKey)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.flush();
                } finally {
                    updateShareTokenAfterDownload(shareTokenEntity, upload);
                }
            };
        }
    }

    /**
     * Deletes the re-encrypted sidecar file for {@code token}, if one exists.
     *
     * <p>No-op for tokens without a {@code shareKeyHash} — unencrypted shares have no sidecar.
     * Called on token expiry, revocation, or download exhaustion.
     */
    public void deleteShareSidecar(ShareTokenEntity token) {
        if (token.shareKeyHash == null || token.file == null) return;
        String sidecarKey = token.file.uuid + "-share-" + token.shareToken;
        if (!storageService.delete(sidecarKey)) {
            logger.warn("Failed to delete share sidecar: {}", sidecarKey);
        } else {
            logger.info("Deleted share sidecar: {}", sidecarKey);
        }
    }

    private ResponseEntity<StreamingResponseBody> createFileDownloadResponse(InputStream inputStream, Upload upload, HttpServletRequest request) {
        StreamingResponseBody responseBody = getStreamingResponseBody(inputStream);
        logger.info("Sending file: {}", upload);
        logHistory(upload, request, EventType.DOWNLOAD);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + URLEncoder.encode(upload.name, StandardCharsets.UTF_8) + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(upload.size))
                .header("X-Accel-Buffering", "no")
                .body(responseBody);
    }

    private void updateShareTokenAfterDownload(ShareTokenEntity shareTokenEntity, Upload upload) {
        if (shareTokenEntity.numberOfAllowedDownloads != null) {
            // Atomic decrement — safe under concurrent downloads.  If another thread already
            // exhausted the count, decrementDownloadCount returns 0 and we still fall through
            // to the cleanup check using a freshly loaded entity.
            shareTokenRepository.decrementDownloadCount(shareTokenEntity.getId());
            // Re-fetch so the cleanup decision is based on the DB-authoritative value.
            shareTokenEntity = shareTokenRepository.findById(shareTokenEntity.getId())
                    .orElse(shareTokenEntity);
        }
        if (!validateShareToken(shareTokenEntity)) {
            deleteShareSidecar(shareTokenEntity);
            shareTokenRepository.deleteByIdTransactional(shareTokenEntity.getId());
        }
        // No explicit save needed for the limited-download path; the decrement was already
        // applied by the @Modifying query above.  Unlimited tokens need no counter update.
        logger.info("Share token updated. Upload streamed successfully: {}", upload.name);
    }

    private boolean isSvgFile(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    private void logHistory(Upload upload, HttpServletRequest request, EventType eventType) {
        var info = getRequesterInfo(request);
        activityLogRepository.save(new ActivityLog(upload, eventType, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(upload, eventType);
    }
}
