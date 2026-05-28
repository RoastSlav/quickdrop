package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.*;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.FileSession;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.safeNumber;
import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileUtils.*;

/**
 * Core service for file lifecycle operations.
 *
 * <p>Responsibilities include:
 * <ul>
 *   <li>Persisting file records after a chunked upload completes ({@link #saveFile}).</li>
 *   <li>Streaming file downloads — plain or AES-decrypted ({@link #downloadFile}).</li>
 *   <li>In-browser file preview with size and type gating ({@link #previewFile}).</li>
 *   <li>Share token generation and share-link streaming ({@link #generateShareToken},
 *       {@link #streamFileByShareToken}).</li>
 *   <li>File metadata mutations: hide/show, extend expiry, keep-indefinitely
 *       ({@link #toggleHidden}, {@link #extendFile}, {@link #updateKeepIndefinitely}).</li>
 *   <li>Deletion from the filesystem and/or soft-deleting database records
 *       ({@link #deleteFileFromFileSystem}, {@link #deleteFileFromDatabaseAndFileSystem(String, String, String)},
 *       {@link #removeFileFromDatabase(String, String, String)}).</li>
 * </ul>
 *
 * <p>Paste-specific operations (create, update, content retrieval, view logging) live
 * in {@link PasteService}.
 *
 * <p>Most mutating methods are annotated with {@link CacheEvict} to keep the
 * {@code publicFiles}, {@code adminFiles}, {@code adminPastes}, and {@code analytics}
 * caches consistent. Paginated list queries are backed by {@link Cacheable} caches
 * keyed by page, size, and optional search query.
 */
@Service
public class FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final UploadRepository uploadRepository;
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final ActivityLogRepository activityLogRepository;
    private final SessionService sessionService;
    private final EncryptionService encryptionService;
    private final SvgRasterizationService svgRasterizationService;
    private final ShareTokenRepository shareTokenRepository;
    private final NotificationService notificationService;
    private final ShareEncryptionService shareEncryptionService;

    public FileService(UploadRepository uploadRepository,
                       FileRepository fileRepository,
                       PasswordEncoder passwordEncoder,
                       ApplicationSettingsService applicationSettingsService,
                       ActivityLogRepository activityLogRepository,
                       SessionService sessionService,
                       EncryptionService encryptionService,
                       SvgRasterizationService svgRasterizationService,
                       ShareTokenRepository shareTokenRepository,
                       NotificationService notificationService,
                       ShareEncryptionService shareEncryptionService) {
        this.uploadRepository = uploadRepository;
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.activityLogRepository = activityLogRepository;
        this.sessionService = sessionService;
        this.encryptionService = encryptionService;
        this.svgRasterizationService = svgRasterizationService;
        this.shareTokenRepository = shareTokenRepository;
        this.notificationService = notificationService;
        this.shareEncryptionService = shareEncryptionService;
    }

    /**
     * Persists a database record for a file that has already been written to disk by
     * {@link AsyncFileMergeService}, logs the upload event, and sends a notification.
     *
     * <p>Returns {@code null} and does nothing if either argument fails
     * {@link org.rostislav.quickdrop.util.DataValidator#validateObjects}.
     *
     * @param file          the merged file on disk (used only for its name in logging)
     * @param uploadRequest metadata from the original upload request
     * @param uuid          the pre-generated UUID assigned to the file on disk
     * @return the saved {@link Upload} ({@link StoredFile} or {@link Paste}),
     * or {@code null} on validation failure
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public Upload saveFile(File file, UploadRequest uploadRequest, String uuid) {
        if (!validateObjects(file, uploadRequest)) {
            return null;
        }

        logger.info("Saving file: {}", file.getName());

        Upload upload = populateUpload(uploadRequest, uuid);

        logger.info("Upload inserted into database: {}", upload);
        Upload saved = uploadRepository.save(upload);

        activityLogRepository.save(new ActivityLog(saved, EventType.UPLOAD, uploadRequest.uploaderIp, uploadRequest.uploaderUserAgent));
        notificationService.notifyFileAction(saved, EventType.UPLOAD);

        return saved;
    }

    /**
     * Maps an {@link UploadRequest} and a pre-assigned UUID into a transient
     * {@link Upload} subtype ({@link StoredFile} or {@link Paste}) ready for persistence.
     *
     * @param request upload metadata
     * @param uuid    UUID to assign to the new entity
     * @return unpersisted {@link Upload}
     */
    private Upload populateUpload(UploadRequest request, String uuid) {
        Upload upload;
        if (request.paste) {
            Paste paste = new Paste();
            paste.editOnly = request.editOnly;
            paste.immutable = request.immutable;
            upload = paste;
        } else {
            StoredFile storedFile = new StoredFile();
            storedFile.folderUpload = request.folderUpload;
            storedFile.folderName = request.folderName;
            storedFile.folderManifest = request.folderManifest;
            upload = storedFile;
        }

        upload.name = request.fileName;
        upload.uuid = uuid;
        upload.description = request.description;
        upload.size = request.fileSize;
        upload.keepIndefinitely = request.keepIndefinitely;
        upload.hidden = request.hidden;
        upload.encrypted = shouldEncrypt(request);

        if (request.password != null && !request.password.isBlank()) {
            upload.passwordHash = passwordEncoder.encode(request.password);
        }

        return upload;
    }

    /**
     * Returns a single upload (file or paste) by UUID.
     *
     * @param uuid the upload UUID
     * @return an {@link Optional} containing the matching entity, or empty if not found
     */
    public Optional<Upload> getFile(String uuid) {
        return uploadRepository.findByUUID(uuid);
    }

    /**
     * Deletes the physical file from the configured storage directory.
     *
     * @param uuid the file UUID (also the filename on disk)
     * @return {@code true} if deleted successfully, {@code false} if deletion failed
     */
    public boolean deleteFileFromFileSystem(String uuid) {
        Path path = Path.of(applicationSettingsService.getFileStoragePath(), uuid);
        try {
            boolean existed = Files.deleteIfExists(path);
            if (existed) {
                logger.info("File deleted: {}", path);
            } else {
                logger.warn("File already absent from filesystem, treating as deleted: {}", path);
            }
        } catch (Exception e) {
            logger.error("Failed to delete file from filesystem: {}", path, e);
            return false;
        }
        return true;
    }

    /**
     * Deletes a file from both the filesystem and the database in a single transaction.
     * If the filesystem deletion fails the database record is not modified and the method
     * returns {@code false}.
     *
     * @param uuid      the file UUID
     * @param ipAddress requester IP address to record in the deletion log, or {@code null}
     * @param userAgent requester User-Agent to record in the deletion log, or {@code null}
     * @return {@code true} if both the filesystem and database deletions succeeded
     */
    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public boolean deleteFileFromDatabaseAndFileSystem(String uuid, String ipAddress, String userAgent) {
        boolean fsRemoved = deleteFileFromFileSystem(uuid);
        if (!fsRemoved) {
            logger.error("Failed to delete file from file system: {}", uuid);
            return false;
        }

        boolean dbRemoved = removeFileFromDatabase(uuid, ipAddress, userAgent);
        if (!dbRemoved) {
            logger.info("File not found in database for deletion: {}", uuid);
            return false;
        }

        return true;
    }

    /**
     * Soft-deletes a file without recording requester metadata (system-initiated).
     * Delegates to {@link #removeFileFromDatabase(String, String, String)}.
     *
     * @param uuid the file UUID
     * @return {@code true} if the record was found and soft-deleted, {@code false} if not found
     */
    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid) {
        return removeFileFromDatabase(uuid, null, null);
    }

    /**
     * Soft-deletes an upload: marks it {@code deleted = true} in the database and revokes
     * all share tokens, but retains the {@link Upload} record and all activity log rows.
     * Does not touch the filesystem — call
     * {@link #deleteFileFromDatabaseAndFileSystem(String, String, String)} to also remove
     * the physical file.
     *
     * <p>Retaining the entity preserves the FK on existing
     * {@link org.rostislav.quickdrop.entity.ActivityLog} rows, so the admin activity page
     * can still show the upload name and link for deletion events.
     *
     * @param uuid      the upload UUID
     * @param ipAddress requester IP address to record in the deletion log, or {@code null}
     * @param userAgent requester User-Agent to record in the deletion log, or {@code null}
     * @return {@code true} if the record was found and soft-deleted, {@code false} if not found
     */
    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid, String ipAddress, String userAgent) {
        Optional<Upload> referenceById = uploadRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        Upload upload = referenceById.get();
        if (upload.deleted) {
            // Already soft-deleted — idempotent, nothing more to do.
            return true;
        }

        notificationService.notifyFileAction(upload, EventType.DELETION);

        // Save deletion log WITH the upload FK so the activity page can still link to it.
        activityLogRepository.save(new ActivityLog(upload, EventType.DELETION, ipAddress, userAgent));

        // Revoke share tokens (download links are now meaningless); keep history logs intact.
        shareTokenRepository.findAllByFile(upload).forEach(this::deleteShareSidecar);
        shareTokenRepository.deleteAllByFile(upload);

        // Soft-delete: mark deleted, save — do NOT remove the entity or its history.
        upload.deleted = true;
        uploadRepository.save(upload);
        return true;
    }

    /**
     * Streams a file to the client as an attachment, decrypting it if necessary.
     *
     * <p>Returns {@code 404} if the file is not found or has been soft-deleted,
     * {@code 500} if decryption fails.
     *
     * @param uuid    the file UUID
     * @param request the HTTP request (used to extract the session token for the file password)
     * @return a streaming download response, or an error response
     */
    public ResponseEntity<StreamingResponseBody> downloadFile(String uuid, HttpServletRequest request) {
        Upload fileEntity = uploadRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
            logger.info("File not found: {}", uuid);
            return ResponseEntity.notFound().build();
        }
        if (fileEntity.deleted) {
            logger.info("Download attempted for soft-deleted file: {}", uuid);
            return ResponseEntity.notFound().build();
        }

        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        String password = getFilePasswordFromSessionToken(request);

        InputStream inputStream;
        if (fileEntity.encrypted) {
            try {
                inputStream = encryptionService.getDecryptedInputStream(filePath.toFile(), password);
            } catch (Exception e) {
                logger.error("Error decrypting file: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } else {
            try {
                inputStream = new FileInputStream(filePath.toFile());
            } catch (FileNotFoundException e) {
                logger.error("File not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
        }

        return createFileDownloadResponse(inputStream, fileEntity, request);
    }

    /**
     * Returns the file content for in-browser preview.
     *
     * <p>Returns {@code 403} if previews are globally disabled, {@code 404} if the file
     * does not exist or has been soft-deleted, {@code 415} if the file type is not
     * previewable (images, plain text, and PDF are supported), and {@code 428} if the
     * file exceeds the configured preview size limit and {@code manualOverride} is
     * {@code false}.
     *
     * <p>SVG files are served with {@code Content-Type: image/png}. If SVG-to-PNG
     * conversion fails the method returns {@code 415}.
     *
     * @param uuid           the file UUID
     * @param request        the HTTP request carrying the session token used for decryption
     * @param manualOverride if {@code true}, bypasses the file-size limit check
     * @return a streaming inline response, or an appropriate error response
     */
    public ResponseEntity<StreamingResponseBody> previewFile(String uuid, HttpServletRequest request, boolean manualOverride) {
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

        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        String password = getFilePasswordFromSessionToken(request);

        InputStream inputStream;
        try {
            if (fileEntity.encrypted) {
                inputStream = encryptionService.getDecryptedInputStream(filePath.toFile(), password);
            } else {
                inputStream = new FileInputStream(filePath.toFile());
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
     * Returns {@code true} if {@code fileName} has a {@code .svg} extension (case-insensitive).
     */
    private boolean isSvgFile(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    /**
     * Returns {@code true} if the current HTTP session is authorised to access the upload.
     *
     * <p>Uploads without a password hash are always accessible.
     *
     * @param uuid    the upload UUID
     * @param request the HTTP request carrying the session
     * @return {@code true} if access is permitted
     */
    public boolean isAuthorizedForFile(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) {
            return false;
        }
        // edit-only pastes: viewing is always permitted; edit auth is enforced separately
        if (upload.isEditOnly()) {
            return true;
        }
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return true;
        }
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        return sessionToken != null && sessionService.validateFileSessionToken(sessionToken.toString(), uuid);
    }

    /**
     * Returns {@code true} if the current HTTP session is authorised to edit the upload.
     *
     * <p>Always returns {@code false} for immutable pastes.
     * Returns {@code true} when no password hash is set.
     *
     * @param uuid    the upload UUID
     * @param request the HTTP request carrying the session
     * @return {@code true} if editing is permitted
     */
    public boolean isAuthorizedToEdit(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) {
            return false;
        }
        if (upload.isImmutable()) {
            return false;
        }
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return true;
        }
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        return sessionToken != null && sessionService.validateFileSessionToken(sessionToken.toString(), uuid);
    }

    /**
     * Logs a download event for a file and sends a download notification.
     * Does nothing if the file UUID is not found.
     *
     * @param uuid    the file UUID
     * @param request the HTTP request (provides requester IP and user-agent)
     */
    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public void logDownload(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) return;
        RequesterInfo requesterInfo = getRequesterInfo(request);
        activityLogRepository.save(new ActivityLog(upload, EventType.DOWNLOAD, requesterInfo.ipAddress(), requesterInfo.userAgent()));
        notificationService.notifyFileAction(upload, EventType.DOWNLOAD);
    }

    /**
     * Extracts the cleartext file password from the file session token stored in the
     * HTTP session, if present.
     *
     * @param request the HTTP request
     * @return the file access password, or {@code null} if no session token is present
     */
    private String getFilePasswordFromSessionToken(HttpServletRequest request) {
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        if (sessionToken == null) {
            return null;
        }

        FileSession fileSession = sessionService.getPasswordForFileSessionToken(sessionToken.toString());
        return fileSession == null ? null : fileSession.getPassword();
    }

    /**
     * Returns a paginated list of non-hidden files, optionally filtered by a search query.
     * Results are cached per page/size/query combination.
     *
     * @param pageable pagination parameters
     * @param query    optional search string; a blank/null value returns all visible files
     * @return a page of matching {@link StoredFile} records
     */
    @Cacheable(value = "publicFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<StoredFile> getVisibleFiles(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findAllNotHiddenFiles(pageable);
        }
        return fileRepository.searchNotHiddenFiles(query, pageable);
    }

    /**
     * Returns the total bytes consumed by all live file-type records (excluding pastes).
     *
     * @return total storage used in bytes
     */
    public long calculateTotalSpaceUsed() {
        return safeNumber(fileRepository.totalFileSizeForFilesOnly());
    }

    /**
     * @return number of non-paste file records
     */
    public long getFileCount() {
        return fileRepository.countFiles();
    }

    /**
     * Returns a paginated list of file records (non-pastes) with pre-aggregated download
     * counts. Results are cached per page/size/query.
     *
     * @param pageable pagination parameters
     * @param query    optional search string
     * @return a page of {@link FileEntityView} projections
     */
    @Cacheable(value = "adminFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntityView> getFilesWithDownloadCounts(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findFilesWithDownloadCounts(pageable);
        }
        return fileRepository.searchFilesWithDownloadCounts(query, pageable);
    }

    /**
     * Returns a paginated list of soft-deleted file records with pre-aggregated download counts.
     * Used by the admin "Deleted" tab on {@code /admin/files}.
     *
     * @param pageable pagination parameters
     * @param query    optional search string
     * @return a page of deleted {@link FileEntityView} projections
     */
    @Cacheable(value = "adminDeletedFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntityView> getDeletedFilesWithDownloadCounts(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findDeletedFilesWithDownloadCounts(pageable);
        }
        return fileRepository.searchDeletedFilesWithDownloadCounts(query, pageable);
    }

    /**
     * Verifies a candidate plaintext password against an upload's stored BCrypt hash.
     *
     * @param uuid     the upload UUID
     * @param password the candidate plaintext password
     * @return {@code true} if the password matches
     */
    public boolean checkFilePassword(String uuid, String password) {
        Optional<Upload> referenceByUUID = uploadRepository.findByUUID(uuid);
        if (referenceByUUID.isEmpty()) {
            return false;
        }

        Upload upload = referenceByUUID.get();
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(password, upload.passwordHash);
    }

    /**
     * Resets an upload's {@code uploadDate} to today, effectively extending its scheduled
     * deletion by {@code maxFileLifeTime} days.
     *
     * @param uuid    the upload UUID
     * @param request the HTTP request (for history logging)
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "adminDeletedPastes"}, allEntries = true)
    public void extendFile(String uuid, HttpServletRequest request) {
        Optional<Upload> referenceById = uploadRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return;
        }

        Upload upload = referenceById.get();
        upload.uploadDate = LocalDate.now();
        logger.info("Upload extended: {}", upload);
        uploadRepository.save(upload);
        logHistory(upload, request, EventType.RENEWAL);
    }

    /**
     * Toggles the {@code hidden} flag on an upload.
     *
     * <p>If {@code hideFromListAdminOnly} is enabled in settings, non-admin requests are
     * silently rejected and the unchanged entity is returned.
     *
     * @param uuid    the upload UUID
     * @param request the HTTP request (used for admin session check)
     * @return the (possibly updated) {@link Upload}, or {@code null} if not found
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "analytics"}, allEntries = true)
    public Upload toggleHidden(String uuid, HttpServletRequest request) {
        Optional<Upload> referenceById = uploadRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            logger.info("Upload not found for 'toggle hidden': {}", uuid);
            return null;
        }

        Upload upload = referenceById.get();

        if (applicationSettingsService.isHideFromListAdminOnly() && (request == null || !sessionService.hasValidAdminSession(request))) {
            logger.info("Hide toggle blocked (admin only) for UUID: {}", uuid);
            return upload;
        }

        upload.hidden = !upload.hidden;
        logger.info("Upload hidden updated: {}", upload);
        uploadRepository.save(upload);
        return upload;
    }

    /**
     * Updates the {@code keepIndefinitely} flag on an upload.
     *
     * <p>When the flag is cleared (set to {@code false}) the upload date is also reset to
     * today via {@link #extendFile}. If the {@code keepIndefinitelyAdminOnly} setting is
     * active, non-admin requests are silently rejected and the unchanged entity is returned.
     *
     * @param uuid             the upload UUID
     * @param keepIndefinitely the new flag value
     * @param request          the HTTP request (for admin session check and history logging)
     * @return the (possibly updated) {@link Upload}, or {@code null} if not found
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public Upload updateKeepIndefinitely(String uuid, boolean keepIndefinitely, HttpServletRequest request) {
        Optional<Upload> referenceById = uploadRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            logger.info("Upload not found for 'update keep indefinitely': {}", uuid);
            return null;
        }

        if (applicationSettingsService.isKeepIndefinitelyAdminOnly() && !sessionService.hasValidAdminSession(request)) {
            logger.info("Keep indefinitely change blocked (admin only) for UUID: {}", uuid);
            return referenceById.get();
        }

        if (!keepIndefinitely) {
            extendFile(uuid, request);
        }

        Upload upload = referenceById.get();
        upload.keepIndefinitely = keepIndefinitely;
        logger.info("Upload keepIndefinitely updated: {}", upload);
        uploadRepository.save(upload);
        return upload;
    }

    /**
     * Saves a history log entry and sends a notification for an upload event.
     *
     * @param upload    the upload that triggered the event
     * @param request   the HTTP request providing requester metadata
     * @param eventType the event type to record
     */
    private void logHistory(Upload upload, HttpServletRequest request, EventType eventType) {
        RequesterInfo info = getRequesterInfo(request);
        activityLogRepository.save(new ActivityLog(upload, eventType, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(upload, eventType);
    }

    /**
     * Generates a unique share token string for the given upload, retrying on collision.
     *
     * @param upload the upload to generate a token for
     * @return a collision-free token string
     */
    private String generateUniqueShareToken(Upload upload) {
        String token;
        do {
            token = generateHashedToken(upload);
        } while (shareTokenRepository.existsByShareToken(token));
        return token;
    }

    /**
     * Looks up an existing unlimited (no expiry, no download limit) share token for an upload.
     *
     * @param upload the upload entity
     * @return an existing unlimited token if one exists
     */
    private java.util.Optional<ShareTokenEntity> findUnlimitedShareToken(Upload upload) {
        return shareTokenRepository.findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(upload);
    }

    /**
     * Generates (or returns an existing) share token for a non-encrypted upload.
     *
     * <p>If both {@code tokenExpirationDate} and {@code numberOfDownloads} are {@code null}
     * and an unlimited token already exists for the upload, it is returned without creating
     * a new one.
     *
     * @param uuid                the upload UUID
     * @param tokenExpirationDate optional expiry date for the token
     * @param numberOfDownloads   optional download limit; {@code null} means unlimited
     * @return the new or existing {@link ShareTokenEntity}
     * @throws IllegalArgumentException if the UUID is not found
     */
    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        Optional<Upload> optionalUpload = uploadRepository.findByUUID(uuid);
        if (optionalUpload.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }
        Upload upload = optionalUpload.get();

        if (tokenExpirationDate == null && numberOfDownloads == null) {
            Optional<ShareTokenEntity> existing = findUnlimitedShareToken(upload);
            if (existing.isPresent()) {
                ShareTokenEntity token = existing.get();
                token.createdAt = LocalDateTime.now();
                return shareTokenRepository.save(token);
            }
        }

        String token = generateUniqueShareToken(upload);
        ShareTokenEntity shareToken = new ShareTokenEntity(token, upload, tokenExpirationDate, numberOfDownloads);
        shareTokenRepository.save(shareToken);

        return shareToken;
    }

    /**
     * Generates a share token for a password-protected upload.
     *
     * <p>For encrypted uploads, a randomly generated share key is BCrypt-hashed and stored
     * in the token; the plaintext key is returned in the result. A sidecar re-encryption
     * task is submitted asynchronously; the returned token has
     * {@link ShareTokenEntity#sidecarReady} set to {@code false} until the task completes.
     *
     * <p>For non-encrypted uploads with a password, delegates to
     * {@link #generateShareToken(String, LocalDate, Integer)} and returns a {@code null}
     * share key with {@code sidecarReady = true}.
     *
     * @param uuid                the upload UUID
     * @param tokenExpirationDate optional expiry date
     * @param sessionToken        file session token (provides the decryption password for encrypted uploads)
     * @param numberOfDownloads   optional download limit
     * @return a result holding the persisted {@link ShareTokenEntity} and the plaintext share key
     * (the key is {@code null} when the upload is not AES-encrypted)
     * @throws IllegalArgumentException if the UUID is not found
     */
    public ShareTokenResult generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, Integer numberOfDownloads) {
        Optional<Upload> optionalUpload = uploadRepository.findByUUID(uuid);
        if (optionalUpload.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        Upload upload = optionalUpload.get();

        if (!upload.encrypted) {
            // Non-encrypted but password-protected: delegate to the plain overload
            ShareTokenEntity shareToken = generateShareToken(uuid, tokenExpirationDate, numberOfDownloads);
            return new ShareTokenResult(shareToken, null);
        }

        // Encrypted: generate a fresh token and kick off sidecar re-encryption in the background
        String shareKey = java.util.UUID.randomUUID().toString();
        String token = generateUniqueShareToken(upload);

        // Pre-fetch the password on the request thread before the async task runs,
        // so the HTTP session is not accessed from a background thread.
        String plainPassword = sessionService.getPasswordForFileSessionToken(sessionToken).getPassword();

        ShareTokenEntity shareToken = new ShareTokenEntity(token, upload, tokenExpirationDate, numberOfDownloads);
        shareToken.shareKeyHash = passwordEncoder.encode(shareKey);
        shareToken.sidecarReady = false;
        shareTokenRepository.save(shareToken);

        shareEncryptionService.encryptSidecarAsync(upload.uuid, token, shareKey, plainPassword,
                shareToken.getId(), shareTokenRepository);

        logger.info("Share token saved; sidecar encryption submitted in background for upload: {}", upload.name);
        return new ShareTokenResult(shareToken, shareKey);
    }

    /**
     * Looks up a share token entity by its token string.
     *
     * @param token the share token string
     * @return the matching {@link ShareTokenEntity}, or empty if not found
     */
    public java.util.Optional<ShareTokenEntity> getShareTokenEntityByToken(String token) {
        return shareTokenRepository.findByShareToken(token);
    }

    /**
     * Records a {@link EventType#SHARE_CREATE} log entry for the given upload.
     *
     * @param upload  the upload for which a share token was created
     * @param request the HTTP request that triggered token generation
     */
    public void logShareCreate(Upload upload, HttpServletRequest request) {
        logHistory(upload, request, EventType.SHARE_CREATE);
    }

    /**
     * Revokes a share token by ID: deletes its sidecar file if present, logs a
     * {@link EventType#SHARE_REVOKE} event against the associated upload, and
     * removes the token row. Does nothing if the token does not exist.
     *
     * @param tokenId the database ID of the token to revoke
     * @param request the HTTP request used for history-log IP/user-agent metadata
     */
    public void revokeShareToken(Long tokenId, HttpServletRequest request) {
        shareTokenRepository.findById(tokenId).ifPresent(token -> {
            deleteShareSidecar(token);
            if (token.file != null) {
                logHistory(token.file, request, EventType.SHARE_REVOKE);
            }
            shareTokenRepository.delete(token);
            logger.info("Share token {} revoked by admin", token.shareToken);
        });
    }

    /**
     * Builds the HTTP response for a file download, including correct
     * {@code Content-Disposition}, {@code Content-Type}, and {@code Content-Length} headers.
     *
     * @param inputStream the (possibly decrypted) content stream
     * @param upload      the file metadata
     * @param request     the HTTP request (for history logging)
     * @return a {@code 200 OK} streaming response
     */
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

    /**
     * Returns {@code true} if the physical file exists in the configured storage directory.
     *
     * @param uuid the file UUID
     * @return {@code true} if the file is present on disk
     */
    public boolean fileExistsInFileSystem(String uuid) {
        return Files.exists(Path.of(applicationSettingsService.getFileStoragePath(), uuid));
    }

    /**
     * Returns a filtered, sorted, paginated page of active share tokens.
     *
     * @param today     today's date used as the expiry cutoff
     * @param isPaste   {@code true} = pastes only, {@code false} = files only, {@code null} = both
     * @param noExpiry  when {@code true} restrict to tokens with no expiry date
     * @param unlimited when {@code true} restrict to tokens with no download cap
     * @param query     optional case-insensitive substring filter on upload name and token string
     * @param pageable  pagination and sort configuration
     * @return page of matching active tokens
     */
    public Page<ShareTokenEntity> getFilteredShareTokens(LocalDate today, Boolean isPaste, boolean noExpiry,
                                                         boolean unlimited, String query, Pageable pageable) {
        return shareTokenRepository.findFiltered(today, isPaste, noExpiry, unlimited, query, pageable);
    }

    /**
     * Returns {@code true} if the upload request should result in an encrypted file on disk.
     * Encryption requires a non-blank password and that encryption is enabled in settings.
     *
     * @param request the upload request
     * @return {@code true} if the file should be AES-encrypted
     */
    public boolean shouldEncrypt(UploadRequest request) {
        // edit-only pastes are stored unencrypted so the content can be served without a password
        return request.password != null && !request.password.isBlank()
                && applicationSettingsService.isEncryptionEnabled()
                && !request.editOnly;
    }

    /**
     * Returns a {@link StreamingResponseBody} that streams the file associated with
     * a share token, decrementing the remaining download count and deleting the token
     * when exhausted.
     *
     * <p>For tokens with a non-null {@link ShareTokenEntity#shareKeyHash}, the sidecar at
     * {@code {uuid}-share-{token}} is decrypted using the share key from the HTTP session.
     *
     * @param shareTokenEntity the validated share token
     * @param request          the HTTP request (for history logging and session key lookup)
     * @return a streaming body, or {@code null} if the token is invalid or the sidecar is missing
     */
    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public StreamingResponseBody streamFileByShareToken(ShareTokenEntity shareTokenEntity, HttpServletRequest request) {
        if (!validateShareToken(shareTokenEntity)) {
            return null;
        }

        Upload upload = shareTokenEntity.file;
        String storagePath = applicationSettingsService.getFileStoragePath();

        if (shareTokenEntity.shareKeyHash != null) {
            Path sidecarPath = Path.of(storagePath, upload.uuid + "-share-" + shareTokenEntity.shareToken);
            if (!Files.exists(sidecarPath)) {
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
                        decIn = encryptionService.getDecryptedInputStream(sidecarPath.toFile(), shareKey);
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
            // Legacy path: stream plaintext sidecar if it exists, otherwise raw file
            logHistory(upload, request, EventType.SHARE_DOWNLOAD);
            Path decryptedFilePath = Path.of(storagePath, upload.uuid + "-decrypted");
            Path filePathToStream = Files.exists(decryptedFilePath)
                    ? decryptedFilePath
                    : Path.of(storagePath, upload.uuid);
            return outputStream -> {
                try {
                    streamFile(filePathToStream, upload.uuid, outputStream);
                } finally {
                    updateShareTokenAfterDownload(shareTokenEntity, upload);
                }
            };
        }
    }

    /**
     * Decrements the remaining download count on a share token after a successful
     * download and deletes the token (and its sidecar) if it is now exhausted or expired.
     *
     * @param shareTokenEntity the share token to update
     * @param upload           the upload that was streamed (used only for logging)
     */
    private void updateShareTokenAfterDownload(ShareTokenEntity shareTokenEntity, Upload upload) {
        if (shareTokenEntity.numberOfAllowedDownloads != null) {
            shareTokenEntity.numberOfAllowedDownloads--;
        }

        if (!validateShareToken(shareTokenEntity)) {
            deleteShareSidecar(shareTokenEntity);
            shareTokenRepository.deleteByIdTransactional(shareTokenEntity.getId());
        } else {
            shareTokenRepository.save(shareTokenEntity);
        }
        logger.info("Share token updated/invalidated. Upload streamed successfully: {}", upload.name);
    }

    /**
     * Deletes the re-encrypted sidecar file for a share token, if one exists.
     * Has no effect when {@link ShareTokenEntity#shareKeyHash} is {@code null}.
     * Silently ignores missing files.
     *
     * @param token the share token whose sidecar should be removed
     */
    void deleteShareSidecar(ShareTokenEntity token) {
        if (token.shareKeyHash == null || token.file == null) return;
        Path sidecar = Path.of(applicationSettingsService.getFileStoragePath(),
                token.file.uuid + "-share-" + token.shareToken);
        try {
            Files.deleteIfExists(sidecar);
            logger.info("Deleted share sidecar: {}", sidecar);
        } catch (IOException e) {
            logger.warn("Failed to delete share sidecar: {}", sidecar);
        }
    }

    /**
     * Immutable holder for the IP address and user-agent string of a requester.
     *
     * @param ipAddress client IP address
     * @param userAgent HTTP {@code User-Agent} header value
     */
    public record RequesterInfo(String ipAddress, String userAgent) {
    }

    /**
     * Holds the result of generating a share token for a password-protected upload.
     *
     * @param token    the persisted share token entity
     * @param shareKey the plaintext share key to embed in the URL, or {@code null} for non-encrypted uploads
     */
    public record ShareTokenResult(ShareTokenEntity token, String shareKey) {
    }
}
