package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.ShortLinkResult;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.service.*;
import org.rostislav.quickdrop.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;
import static org.springframework.http.ResponseEntity.ok;

/**
 * REST API for file upload, share-link generation, and share-link downloads.
 *
 * <p>Three endpoints are exposed under {@code /api/file}:
 * <ul>
 *   <li>{@code POST /api/file/upload-chunk} — receives a single chunk of a
 *       multi-part chunked upload, delegates to {@link AsyncFileMergeService},
 *       and returns the saved {@link Upload} JSON on the last chunk.</li>
 *   <li>{@code POST /api/file/share/{uuid}} — generates a share token for a file
 *       and returns the share path immediately. For encrypted files the sidecar
 *       re-encryption is performed in the background by {@link org.rostislav.quickdrop.service.ShareEncryptionService};
 *       the response includes a {@code preparingMessage} flag (string {@code "true"})
 *       when the file exceeds 50 MB so the UI can inform the creator. Simplified
 *       and disabled share-link settings are enforced here.</li>
 *   <li>{@code GET /api/file/download/{token}} — streams a file identified by its
 *       share token. Returns 503 if the sidecar is not yet ready. Redirects (302) to
 *       {@code /share/{token}} when the token is invalid, exhausted, or the sidecar
 *       file is missing; the missing-sidecar path also deletes the broken token so the
 *       share page renders the invalid view on arrival. The download counter is
 *       decremented atomically by {@link FileDownloadService#streamFileByShareToken}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/file")
public class FileRestController {
    private static final Logger logger = LoggerFactory.getLogger(FileRestController.class);
    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
    /** Mirrors {@code AsyncFileMergeService}'s own check so a bad id yields 400, not 500. */
    private static final java.util.regex.Pattern SAFE_UPLOAD_ID =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private final FileQueryService fileQueryService;
    private final FileLifecycleService fileLifecycleService;
    private final FileDownloadService fileDownloadService;
    private final SessionService sessionService;
    private final AsyncFileMergeService asyncFileMergeService;
    private final ApplicationSettingsService applicationSettingsService;
    private final QrCodeService qrCodeService;

    public FileRestController(FileQueryService fileQueryService,
                              FileLifecycleService fileLifecycleService,
                              FileDownloadService fileDownloadService,
                              SessionService sessionService,
                              AsyncFileMergeService asyncFileMergeService,
                              ApplicationSettingsService applicationSettingsService,
                              QrCodeService qrCodeService) {
        this.fileQueryService = fileQueryService;
        this.fileLifecycleService = fileLifecycleService;
        this.fileDownloadService = fileDownloadService;
        this.sessionService = sessionService;
        this.asyncFileMergeService = asyncFileMergeService;
        this.applicationSettingsService = applicationSettingsService;
        this.qrCodeService = qrCodeService;
    }

    /**
     * A manifest is client-supplied text that gets parsed and then stored verbatim in a TEXT
     * column, so its length has to be bounded here. A thousand files with long paths stays
     * comfortably under this; anything larger is not a selection the uploader could have made
     * through the page.
     */
    private static final int MAX_MANIFEST_LENGTH = 1024 * 1024;

    private static String sanitizeArchiveManifest(String manifest, boolean isArchiveUpload) {
        if (!isArchiveUpload || manifest == null || manifest.isBlank()) {
            return null;
        }
        try {
            OBJECT_MAPPER.readTree(manifest);
        } catch (Exception e) {
            return null;
        }
        return manifest.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<?> handleChunkUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileName") String fileName,
            @RequestParam("chunkNumber") int chunkNumber,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam(value = "fileSize", required = false) Long fileSize,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "keepIndefinitely", defaultValue = "false") Boolean keepIndefinitely,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "hidden", defaultValue = "false") Boolean hidden,
            @RequestParam(value = "archiveUpload", defaultValue = "false") Boolean archiveUpload,
            @RequestParam(value = "archiveName", required = false) String archiveName,
            @RequestParam(value = "archiveManifest", required = false) String archiveManifest,
            @RequestParam(value = "uploadId", required = false) String uploadId,
            HttpServletRequest request) {

        boolean isAdmin = sessionService.hasValidAdminSession(request);
        if (!isAdmin && !applicationSettingsService.isUploadEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "File uploads are currently disabled."));
        }
        if (!isAdmin && applicationSettingsService.isUploadAdminOnly()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Uploads are restricted to administrators."));
        }

        // Reject zero-byte uploads early: S3 multipart upload requires at least one byte,
        // and there is no value in storing an empty file regardless of backend.
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty files cannot be uploaded."));
        }
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileName is required."));
        }
        if (totalChunks <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "totalChunks must be greater than zero."));
        }
        if (chunkNumber < 0 || chunkNumber >= totalChunks) {
            return ResponseEntity.badRequest().body(Map.of("error", "chunkNumber must be between 0 and totalChunks - 1."));
        }

        if (chunkNumber == 0) {
            logger.info("Upload started for file: {}", fileName);
        }

        try {
            logger.info("Submitting chunk {} of {} for file: {}", chunkNumber, totalChunks, fileName);

            boolean uploadPasswordEnabled = applicationSettingsService.isUploadPasswordEnabled();
            if (!uploadPasswordEnabled && password != null && !password.isBlank()) {
                return ResponseEntity.badRequest().body("{\"error\": \"Upload passwords are disabled\"}");
            }

            boolean adminSession = sessionService.hasValidAdminSession(request);
            boolean allowKeepIndefinitely = !applicationSettingsService.isKeepIndefinitelyAdminOnly() || adminSession;
            boolean keepIndefinitelyValue = allowKeepIndefinitely && Boolean.TRUE.equals(keepIndefinitely);
            boolean allowHideFromList = !applicationSettingsService.isHideFromListAdminOnly() || adminSession;
            boolean hiddenValue = allowHideFromList && Boolean.TRUE.equals(hidden);

            String forwardedFor = request.getHeader("X-Forwarded-For");
            String uploaderIp = forwardedFor != null && !forwardedFor.isBlank() ? forwardedFor.split(",")[0].trim() : request.getRemoteAddr();
            String uploaderUserAgent = request.getHeader("User-Agent");

            String effectivePassword = uploadPasswordEnabled ? password : null;

            if (archiveManifest != null && archiveManifest.length() > MAX_MANIFEST_LENGTH) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Archive manifest exceeds " + MAX_MANIFEST_LENGTH + " characters."));
            }

            String safeManifest = sanitizeArchiveManifest(archiveManifest, Boolean.TRUE.equals(archiveUpload));
            if (safeManifest == null && archiveManifest != null) {
                return ResponseEntity.badRequest().body("{\"error\": \"Invalid folder manifest: must be a JSON array\"}");
            }

            // A missing uploadId falls back to a random UUID, safe only for single-chunk/legacy
            // clients since all chunks of one upload must share an id. Format-check a supplied
            // id here so a bad one is a 400, not a 500 from the service's filesystem-path guard.
            if (uploadId != null && !uploadId.isBlank() && !SAFE_UPLOAD_ID.matcher(uploadId).matches()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "uploadId must be 1-64 characters of letters, digits, '-' or '_'."));
            }
            String effectiveUploadId = (uploadId != null && !uploadId.isBlank())
                    ? uploadId
                    : UUID.randomUUID().toString();

            UploadRequest fileUploadRequest = new UploadRequest(description, keepIndefinitelyValue, effectivePassword, hiddenValue, fileName, totalChunks, fileSize, uploaderIp, uploaderUserAgent, Boolean.TRUE.equals(archiveUpload), archiveName, safeManifest, false);
            fileUploadRequest.uploadId = effectiveUploadId;
            boolean isLastChunk = chunkNumber == totalChunks - 1;
            Upload upload = asyncFileMergeService.submitChunk(fileUploadRequest, file, chunkNumber, false);
            if (isLastChunk) {
                return ResponseEntity.accepted()
                        .body(Map.of("status", "processing", "uploadId", effectiveUploadId));
            }
            return ResponseEntity.ok(upload);
        } catch (UploadAbortedException e) {
            // Deliberate, expected condition (the client called upload-abort, or the task was
            // TTL-evicted) -- not a server error, so it must not map to the same 500 a genuine
            // I/O failure below gets.
            logger.info("Chunk {} for file {} rejected: upload was aborted", chunkNumber, fileName);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This upload was aborted."));
        } catch (IOException e) {
            logger.error("Error processing chunk {} for file {}: {}", chunkNumber, fileName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Error processing chunk\"}");
        }
    }

    @PostMapping("/upload-abort")
    public ResponseEntity<Void> abortChunkUpload(
            @RequestParam(value = "uploadId", required = false) String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        asyncFileMergeService.abortUpload(uploadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/upload-status/{uploadId}")
    public ResponseEntity<AsyncFileMergeService.UploadProgress> getUploadStatus(@PathVariable String uploadId) {
        return ResponseEntity.ok(asyncFileMergeService.getUploadStatus(uploadId));
    }

    /**
     * Creates or returns a share token for the file identified by {@code uuid}.
     *
     * <p>The JSON response always contains {@code token}, {@code sharePath}, and
     * {@code preparingMessage}. {@code preparingMessage} is the string {@code "true"}
     * when the file is AES-encrypted, the sidecar has not yet finished encrypting in the
     * background, and the file is at least 50 MB (large enough that the delay is
     * noticeable). The frontend uses this flag to show a transient notice to the creator.
     *
     * @param uuid              the file UUID
     * @param expirationDate    optional expiry date for the token
     * @param numberOfDownloads optional download limit; {@code null} means unlimited
     * @param request           the HTTP request (for session and audit logging)
     * @return 200 with token/sharePath/preparingMessage, 400 on bad input, 403 when
     * share links are disabled or the file session is invalid
     */
    @PostMapping("/share/{uuid}")
    public ResponseEntity<Map<String, String>> generateShareableLink(@PathVariable String uuid,
                                                                     @RequestParam(value = "expirationDate", required = false) LocalDate expirationDate,
                                                                     @RequestParam(value = "nOfDownloads", required = false) Integer numberOfDownloads,
                                                                     HttpServletRequest request) {
        if (!applicationSettingsService.isShareLinksEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Share links are disabled."));
        }
        if (applicationSettingsService.isSimplifiedShareLinksEnabled()) {
            expirationDate = null;
            numberOfDownloads = null;
        }
        Upload fileEntity = fileQueryService.getFile(uuid).orElse(null);
        if (fileEntity == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "File not found."));
        }

        if (numberOfDownloads != null && numberOfDownloads < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Number of downloads cannot be negative."));
        }

        String sharePath;
        String tokenString;
        boolean preparingMessage = false;
        if (fileEntity.passwordHash != null && !fileEntity.passwordHash.isEmpty()) {
            String sessionToken = (String) request.getSession().getAttribute("file-session-token");
            if (sessionToken == null || !sessionService.validateFileSessionToken(sessionToken, uuid)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Invalid file session."));
            }
            ShortLinkResult result = fileLifecycleService.generateShareToken(uuid, expirationDate, sessionToken, numberOfDownloads);
            tokenString = result.link().code;
            sharePath = FileUtils.getSharePath(tokenString);
            if (result.shareKey() != null) {
                // Use URL fragment so the key is never sent to the server in HTTP requests
                sharePath += "#key=" + URLEncoder.encode(result.shareKey(), StandardCharsets.UTF_8);
            }
            // Warn the creator only for large files; small files encrypt quickly in the background
            if (!result.link().sidecarReady && fileEntity.size >= 50L * 1024 * 1024) {
                preparingMessage = true;
            }
        } else {
            UploadShareLink token = fileLifecycleService.generateShareToken(uuid, expirationDate, numberOfDownloads);
            tokenString = token.code;
            sharePath = FileUtils.getSharePath(tokenString);
        }
        fileLifecycleService.logShareCreate(fileEntity, request);
        return ok(Map.of(
                "token", tokenString,
                "sharePath", sharePath,
                "preparingMessage", String.valueOf(preparingMessage)
        ));
    }

    /**
     * Returns an SVG QR code for a file's own page URL, used by the share panel when nothing
     * gates the file.
     *
     * <p>The URL is derived here from the uuid rather than accepted from the caller, so this
     * can't be used to render a QR for an arbitrary destination. Only publicly accessible
     * files are served: for a gated one it would answer for a page the caller can't open,
     * turning this into an existence oracle.
     *
     * @param uuid the file's uuid
     * @param size rendered width/height in pixels; clamped by {@link QrCodeService}
     * @return 200 with the SVG body, or 404 when the file is unknown, deleted, or gated
     */
    @GetMapping(value = "/{uuid}/qr.svg", produces = "image/svg+xml")
    public ResponseEntity<String> pageQrSvg(@PathVariable String uuid,
                                            @RequestParam(defaultValue = "150") int size,
                                            HttpServletRequest request) {
        Optional<Upload> fileEntity = fileQueryService.getFile(uuid);
        if (fileEntity.isEmpty() || fileEntity.get().deleted || !isPubliclyAccessible(fileEntity.get())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            String svg = qrCodeService.renderSvg(FileUtils.getDownloadLink(request, fileEntity.get()),
                    size, "#000000", "#ffffff");
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(svg);
        } catch (QrGenerationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).build();
        }
    }

    /** Mirrors the flag FileViewController puts on the model; kept in sync deliberately. */
    private boolean isPubliclyAccessible(Upload fileEntity) {
        return (fileEntity.passwordHash == null || fileEntity.passwordHash.isBlank())
                && !applicationSettingsService.isAppPasswordEnabled();
    }

    @GetMapping("/download/{token}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String token, HttpServletRequest request) {
        try {
            Optional<UploadShareLink> shareTokenEntity = fileQueryService.getShareTokenEntityByToken(token);
            if (shareTokenEntity.isEmpty() || !validateShareToken(shareTokenEntity.get())) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/share/" + token))
                        .build();
            }

            UploadShareLink tokenEntity = shareTokenEntity.get();
            if (!tokenEntity.sidecarReady) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            Upload fileEntity = tokenEntity.upload;
            StreamingResponseBody responseBody = fileDownloadService.streamFileByShareToken(tokenEntity, request);

            if (responseBody == null) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/share/" + token))
                        .build();
            }

            return ok()
                    .header("Content-Disposition", "attachment; filename=\""
                            + URLEncoder.encode(fileEntity.name, StandardCharsets.UTF_8) + "\"")
                    .header("Content-Type", "application/octet-stream")
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
