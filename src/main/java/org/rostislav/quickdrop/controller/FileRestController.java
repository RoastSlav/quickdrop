package org.rostislav.quickdrop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.AsyncFileMergeService;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;
import static org.springframework.http.ResponseEntity.ok;

/**
 * REST API for file upload, share-link generation, and share-link downloads.
 *
 * <p>Three endpoints are exposed under {@code /api/file}:
 * <ul>
 *   <li>{@code POST /api/file/upload-chunk} — receives a single chunk of a
 *       multi-part chunked upload, delegates to {@link AsyncFileMergeService},
 *       and returns the saved {@link FileEntity} JSON on the last chunk.</li>
 *   <li>{@code POST /api/file/share/{uuid}} — generates a share token for a file
 *       and returns the share path immediately. For encrypted files the sidecar
 *       re-encryption is performed in the background by {@link org.rostislav.quickdrop.service.ShareEncryptionService};
 *       the response includes a {@code preparingMessage} flag (string {@code "true"})
 *       when the file exceeds 50 MB so the UI can inform the creator. Simplified
 *       and disabled share-link settings are enforced here.</li>
 *   <li>{@code GET /api/file/download/{token}} — streams a file identified by its
 *       share token. Returns 503 if the sidecar is not yet ready, 403 if the token
 *       is invalid or the sidecar file is missing. The download counter is decremented
 *       atomically by {@link FileService#streamFileByShareToken}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/file")
public class FileRestController {
    private static final Logger logger = LoggerFactory.getLogger(FileRestController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final FileService fileService;
    private final SessionService sessionService;
    private final AsyncFileMergeService asyncFileMergeService;
    private final ApplicationSettingsService applicationSettingsService;

    public FileRestController(FileService fileService, SessionService sessionService, AsyncFileMergeService asyncFileMergeService, ApplicationSettingsService applicationSettingsService) {
        this.fileService = fileService;
        this.sessionService = sessionService;
        this.asyncFileMergeService = asyncFileMergeService;
        this.applicationSettingsService = applicationSettingsService;
    }

    private static String sanitizeFolderManifest(String manifest, boolean isFolderUpload) {
        if (!isFolderUpload || manifest == null || manifest.isBlank()) {
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
            @RequestParam(value = "folderUpload", defaultValue = "false") Boolean folderUpload,
            @RequestParam(value = "folderName", required = false) String folderName,
            @RequestParam(value = "folderManifest", required = false) String folderManifest,
            HttpServletRequest request) {

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

            String safeManifest = sanitizeFolderManifest(folderManifest, Boolean.TRUE.equals(folderUpload));
            if (safeManifest == null && folderManifest != null) {
                return ResponseEntity.badRequest().body("{\"error\": \"Invalid folder manifest: must be a JSON array\"}");
            }

            FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitelyValue, effectivePassword, hiddenValue, fileName, totalChunks, fileSize, uploaderIp, uploaderUserAgent, Boolean.TRUE.equals(folderUpload), folderName, safeManifest, false);
            FileEntity fileEntity = asyncFileMergeService.submitChunk(fileUploadRequest, file, chunkNumber);
            return ResponseEntity.ok(fileEntity);
        } catch (IOException e) {
            logger.error("Error processing chunk {} for file {}: {}", chunkNumber, fileName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Error processing chunk\"}");
        }
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
        if (applicationSettingsService.isShareLinksDisabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Share links are disabled."));
        }
        if (applicationSettingsService.isSimplifiedShareLinksEnabled()) {
            expirationDate = null;
            numberOfDownloads = null;
        }
        FileEntity fileEntity = fileService.getFile(uuid);
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
            FileService.ShareTokenResult result = fileService.generateShareToken(uuid, expirationDate, sessionToken, numberOfDownloads);
            tokenString = result.token().shareToken;
            sharePath = FileUtils.getSharePath(tokenString);
            if (result.shareKey() != null) {
                sharePath += "?key=" + URLEncoder.encode(result.shareKey(), StandardCharsets.UTF_8);
            }
            // Warn the creator only for large files; small files encrypt quickly in the background
            if (!result.token().sidecarReady && fileEntity.size >= 50L * 1024 * 1024) {
                preparingMessage = true;
            }
        } else {
            ShareTokenEntity token = fileService.generateShareToken(uuid, expirationDate, numberOfDownloads);
            tokenString = token.shareToken;
            sharePath = FileUtils.getSharePath(tokenString);
        }
        fileService.logShareCreate(fileEntity, request);
        return ok(Map.of(
                "token", tokenString,
                "sharePath", sharePath,
                "preparingMessage", String.valueOf(preparingMessage)
        ));
    }

    /**
     * Streams the file associated with the given share token.
     *
     * <p>Returns 403 when the token is missing, expired, exhausted, or the sidecar file
     * has been removed. Returns 503 when the token is valid but the sidecar re-encryption
     * is still running in the background ({@link org.rostislav.quickdrop.entity.ShareTokenEntity#sidecarReady}
     * is {@code false}). On success the response carries
     * {@code Content-Disposition: attachment} so browsers prompt a save dialog.
     *
     * @param token   the share token string from the URL
     * @param request the HTTP request (for session key lookup and history logging)
     * @return 200 with the file byte stream, 403 on invalid/missing token, 503 if not ready
     */
    @GetMapping("/download/{token}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String token, HttpServletRequest request) {
        try {
            Optional<ShareTokenEntity> shareTokenEntity = fileService.getShareTokenEntityByToken(token);
            if (shareTokenEntity.isEmpty() || !validateShareToken(shareTokenEntity.get())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            ShareTokenEntity tokenEntity = shareTokenEntity.get();
            if (!tokenEntity.sidecarReady) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            FileEntity fileEntity = tokenEntity.file;
            StreamingResponseBody responseBody = fileService.streamFileByShareToken(tokenEntity, request);

            if (responseBody == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            return ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileEntity.name + "\"")
                    .header("Content-Type", "application/octet-stream")
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
