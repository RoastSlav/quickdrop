package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.*;
import org.rostislav.quickdrop.repository.FileHistoryLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
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
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.safeNumber;
import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileUtils.*;

/**
 * Core service for all file and paste lifecycle operations.
 *
 * <p>Responsibilities include:
 * <ul>
 *   <li>Persisting file records after a chunked upload completes ({@link #saveFile}).</li>
 *   <li>Streaming file downloads — plain or AES-decrypted ({@link #downloadFile}).</li>
 *   <li>In-browser file preview with size and type gating ({@link #previewFile}).</li>
 *   <li>Paste creation, editing, and content retrieval ({@link #createPaste},
 *       {@link #updatePaste}, {@link #getPasteContent}).</li>
 *   <li>Share token generation and share-link streaming ({@link #generateShareToken},
 *       {@link #streamFileByShareToken}).</li>
 *   <li>File metadata mutations: hide/show, extend expiry, keep-indefinitely
 *       ({@link #toggleHidden}, {@link #extendFile}, {@link #updateKeepIndefinitely}).</li>
 *   <li>Deletion from the filesystem and/or database ({@link #deleteFileFromFileSystem},
 *       {@link #deleteFileFromDatabaseAndFileSystem}, {@link #removeFileFromDatabase}).</li>
 * </ul>
 *
 * <p>Several methods are annotated with {@link CacheEvict} to keep the {@code publicFiles},
 * {@code adminFiles}, {@code adminPastes}, and {@code analytics} caches consistent.
 * Paginated list queries are backed by {@link Cacheable} caches keyed by page, size, and
 * optional search query.
 */
@Service
public class FileService {
    public static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final FileHistoryLogRepository fileHistoryLogRepository;
    private final SessionService sessionService;
    private final FileEncryptionService fileEncryptionService;
    private final SvgRasterizationService svgRasterizationService;
    private final ShareTokenRepository shareTokenRepository;
    private final NotificationService notificationService;
    private final AsyncFileMergeService asyncFileMergeService;
    private final ShareEncryptionService shareEncryptionService;

    @Lazy
    public FileService(FileRepository fileRepository, PasswordEncoder passwordEncoder, ApplicationSettingsService applicationSettingsService, FileHistoryLogRepository fileHistoryLogRepository, SessionService sessionService, FileEncryptionService fileEncryptionService, SvgRasterizationService svgRasterizationService, ShareTokenRepository shareTokenRepository, NotificationService notificationService, @Lazy AsyncFileMergeService asyncFileMergeService, ShareEncryptionService shareEncryptionService) {
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.fileHistoryLogRepository = fileHistoryLogRepository;
        this.sessionService = sessionService;
        this.fileEncryptionService = fileEncryptionService;
        this.svgRasterizationService = svgRasterizationService;
        this.shareTokenRepository = shareTokenRepository;
        this.notificationService = notificationService;
        this.asyncFileMergeService = asyncFileMergeService;
        this.shareEncryptionService = shareEncryptionService;
    }

    /**
     * Persists a database record for a file that has already been written to disk by
     * {@link AsyncFileMergeService}, logs the upload event, and sends a notification.
     *
     * <p>Returns {@code null} and does nothing if either argument fails
     * {@link org.rostislav.quickdrop.util.DataValidator#validateObjects}.
     *
     * @param file              the merged file on disk (used only for its name in logging)
     * @param fileUploadRequest metadata from the original upload request
     * @param uuid              the pre-generated UUID assigned to the file on disk
     * @return the saved {@link FileEntity}, or {@code null} on validation failure
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "analytics"}, allEntries = true)
    public FileEntity saveFile(File file, FileUploadRequest fileUploadRequest, String uuid) {
        if (!validateObjects(file, fileUploadRequest)) {
            return null;
        }

        logger.info("Saving file: {}", file.getName());

        FileEntity fileEntity = populateFileEntity(fileUploadRequest, uuid);

        logger.info("FileEntity inserted into database: {}", fileEntity);
        FileEntity saved = fileRepository.save(fileEntity);

        fileHistoryLogRepository.save(new FileHistoryLog(saved, FileHistoryType.UPLOAD, fileUploadRequest.uploaderIp, fileUploadRequest.uploaderUserAgent));
        notificationService.notifyFileAction(saved, FileHistoryType.UPLOAD);

        return saved;
    }

    /**
     * Returns all file records from the database (pastes and files combined).
     * Prefer the paginated variants for large datasets.
     *
     * @return all persisted {@link FileEntity} rows
     */
    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    /**
     * Maps a {@link FileUploadRequest} and a pre-assigned UUID into a transient
     * {@link FileEntity} ready for persistence.
     *
     * @param request upload metadata
     * @param uuid    UUID to assign to the new entity
     * @return unpersisted {@link FileEntity}
     */
    private FileEntity populateFileEntity(FileUploadRequest request, String uuid) {
        FileEntity fileEntity = new FileEntity();
        fileEntity.name = request.fileName;
        fileEntity.uuid = uuid;
        fileEntity.description = request.description;
        fileEntity.size = request.fileSize;
        fileEntity.keepIndefinitely = request.keepIndefinitely;
        fileEntity.hidden = request.hidden;
        fileEntity.encrypted = shouldEncrypt(request);
        fileEntity.folderUpload = request.folderUpload;
        fileEntity.folderName = request.folderName;
        fileEntity.folderManifest = request.folderManifest;
        fileEntity.paste = request.paste;

        if (request.password != null && !request.password.isBlank()) {
            fileEntity.passwordHash = passwordEncoder.encode(request.password);
        }

        return fileEntity;
    }

    /**
     * Returns a single file entity by UUID.
     *
     * @param uuid the file UUID
     * @return the matching {@link FileEntity}, or {@code null} if not found
     */
    public FileEntity getFile(String uuid) {
        return fileRepository.findByUUID(uuid).orElse(null);
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
            Files.delete(path);
            logger.info("File deleted: {}", path);
        } catch (
                Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Deletes a file from both the filesystem and the database in a single transaction.
     * The filesystem deletion is attempted first; if it fails the database record is left
     * intact so the discrepancy can be resolved later by the maintenance schedule.
     *
     * @param uuid the file UUID
     * @return {@code true} if both deletions succeeded
     */
    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "analytics"}, allEntries = true)
    public boolean deleteFileFromDatabaseAndFileSystem(String uuid) {
        boolean fsRemoved = deleteFileFromFileSystem(uuid);
        if (!fsRemoved) {
            logger.error("Failed to delete file from file system: {}", uuid);
            return false;
        }

        boolean dbRemoved = removeFileFromDatabase(uuid);
        if (!dbRemoved) {
            logger.info("File not found in database for deletion: {}", uuid);
            return false;
        }

        return true;
    }

    /**
     * Removes a file record from the database along with its share tokens and history logs,
     * and sends a deletion notification. Does not touch the filesystem.
     *
     * <p>All share sidecars ({@code {uuid}-share-{token}}) are deleted before the token
     * rows are removed so no orphaned encrypted sidecars remain on disk.
     *
     * @param uuid the file UUID
     * @return {@code true} if the record was found and removed, {@code false} if not found
     */
    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DELETION);

        shareTokenRepository.findAllByFile(fileEntity).forEach(this::deleteShareSidecar);
        shareTokenRepository.deleteAllByFile(fileEntity);
        fileHistoryLogRepository.deleteByFileId(fileEntity.id);
        fileRepository.delete(fileEntity);
        return true;
    }

    /**
     * Streams a file to the client as an attachment, decrypting it if necessary.
     *
     * <p>The file password is read from the session token stored in the HTTP session.
     * Returns {@code 404} if the file is not found, {@code 500} if decryption fails.
     *
     * @param uuid    the file UUID
     * @param request the HTTP request (used to extract the session token for the file password)
     * @return a streaming download response, or an error response
     */
    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public ResponseEntity<StreamingResponseBody> downloadFile(String uuid, HttpServletRequest request) {
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
            logger.info("File not found: {}", uuid);
            return ResponseEntity.notFound().build();
        }

        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        String password = getFilePasswordFromSessionToken(request);

        InputStream inputStream;
        if (fileEntity.encrypted) {
            try {
                inputStream = fileEncryptionService.getDecryptedInputStream(filePath.toFile(), password);
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
     * <p>Returns {@code 403} if previews are globally disabled, {@code 404} if the file does
     * not exist, {@code 415} if the file type is not previewable (images, plain text, and PDF
     * are supported), and {@code 428} if the file exceeds the configured preview size limit
     * and {@code manualOverride} is {@code false}.
     *
     * <p>SVG files are transcoded to PNG via {@link SvgRasterizationService#rasterizeToPng}
     * before streaming. If transcoding fails the method returns {@code 415}.
     *
     * @param uuid           the file UUID
     * @param request        the HTTP request carrying the session token used for decryption
     * @param manualOverride if {@code true}, bypasses the file-size limit check
     * @return a streaming inline response, or an appropriate error response
     */
    public ResponseEntity<StreamingResponseBody> previewFile(String uuid, HttpServletRequest request, boolean manualOverride) {
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
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
                inputStream = fileEncryptionService.getDecryptedInputStream(filePath.toFile(), password);
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

    /** Returns {@code true} if {@code fileName} has a {@code .svg} extension (case-insensitive). */
    private boolean isSvgFile(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    /**
     * Returns {@code true} if the current HTTP session is authorised to access the file.
     *
     * <p>Files without a password hash are always accessible. Password-protected files
     * require a valid file session token in the HTTP session that is bound to the requested UUID.
     *
     * @param uuid    the file UUID
     * @param request the HTTP request carrying the session
     * @return {@code true} if access is permitted
     */
    public boolean isAuthorizedForFile(String uuid, HttpServletRequest request) {
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
            return false;
        }
        if (fileEntity.passwordHash == null || fileEntity.passwordHash.isBlank()) {
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
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) return;
        RequesterInfo requesterInfo = getRequesterInfo(request);
        fileHistoryLogRepository.save(new FileHistoryLog(fileEntity, FileHistoryType.DOWNLOAD, requesterInfo.ipAddress, requesterInfo.userAgent));
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DOWNLOAD);
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
     * @return a page of matching {@link FileEntity} records
     */
    @Cacheable(value = "publicFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntity> getVisibleFiles(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findAllNotHiddenFiles(pageable);
        }
        return fileRepository.searchNotHiddenFiles(query, pageable);
    }

    /**
     * Returns the total bytes consumed by all file-type records (excluding pastes).
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
        return fileRepository.countByPasteFalse();
    }

    /** @return number of paste records */
    public long getPasteCount() {
        return fileRepository.countByPasteTrue();
    }

    /**
     * Returns the average size of paste content in bytes.
     *
     * @return average paste size, or {@code 0.0} if there are no pastes
     */
    public double getAveragePasteLength() {
        Double avg = fileRepository.averagePasteLength();
        return avg != null ? avg : 0.0;
    }

    /** @return number of paste records whose filename ends with {@code .md} */
    public long getMarkdownPasteCount() {
        return fileRepository.countMarkdownPastes();
    }

    /**
     * Returns a paginated list of pastes with pre-aggregated view counts, optionally
     * filtered by a search query. Results are cached per page/size/query combination.
     *
     * @param pageable pagination parameters
     * @param query    optional search string
     * @return a page of {@link PasteEntityView} projections
     */
    @Cacheable(value = "adminPastes", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<PasteEntityView> getPaginatedPastes(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findPastesWithViewCounts(pageable);
        }
        return fileRepository.searchPastesWithViewCounts(query, pageable);
    }

    /**
     * Logs a {@link FileHistoryType#PASTE_VIEW} event for a paste.
     * Does nothing if the UUID is not found or does not refer to a paste.
     *
     * @param uuid    the paste UUID
     * @param request the HTTP request providing requester metadata
     */
    @CacheEvict(value = {"adminPastes", "analytics"}, allEntries = true)
    public void logPasteView(String uuid, HttpServletRequest request) {
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null || !fileEntity.paste) return;
        RequesterInfo info = getRequesterInfo(request);
        fileHistoryLogRepository.save(new FileHistoryLog(fileEntity, FileHistoryType.PASTE_VIEW, info.ipAddress(), info.userAgent()));
    }

    /**
     * Resets a file's {@code uploadDate} to today, effectively extending its scheduled
     * deletion by {@code maxFileLifeTime} days.
     *
     * @param uuid    the file UUID
     * @param request the HTTP request (for history logging)
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
    public void extendFile(String uuid, HttpServletRequest request) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return;
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.uploadDate = LocalDate.now();
        logger.info("File extended: {}", fileEntity);
        fileRepository.save(fileEntity);
        logHistory(fileEntity, request, FileHistoryType.RENEWAL);
    }

    /**
     * Toggles the {@code hidden} flag on a file.
     *
     * <p>If {@code hideFromListAdminOnly} is enabled in settings, non-admin requests are
     * silently rejected and the unchanged entity is returned.
     *
     * @param uuid    the file UUID
     * @param request the HTTP request (used for admin session check)
     * @return the (possibly updated) {@link FileEntity}, or {@code null} if not found
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
    public FileEntity toggleHidden(String uuid, HttpServletRequest request) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            logger.info("File not found for 'toggle hidden': {}", uuid);
            return null;
        }

        FileEntity fileEntity = referenceById.get();

        if (applicationSettingsService.isHideFromListAdminOnly() && (request == null || !sessionService.hasValidAdminSession(request))) {
            logger.info("Hide toggle blocked (admin only) for file UUID: {}", uuid);
            return fileEntity;
        }

        fileEntity.hidden = !fileEntity.hidden;
        logger.info("File hidden updated: {}", fileEntity);
        fileRepository.save(fileEntity);
        return fileEntity;
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
     * Verifies a candidate plaintext password against a file's stored BCrypt hash.
     *
     * @param uuid     the file UUID
     * @param password the candidate plaintext password
     * @return {@code true} if the password matches
     */
    public boolean checkFilePassword(String uuid, String password) {
        Optional<FileEntity> referenceByUUID = fileRepository.findByUUID(uuid);
        if (referenceByUUID.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceByUUID.get();
        if (fileEntity.passwordHash == null || fileEntity.passwordHash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(password, fileEntity.passwordHash);
    }

    /**
     * Creates a new paste from the provided title, content, and syntax hint.
     *
     * <p>The content is written to disk as a single-chunk upload via
     * {@link AsyncFileMergeService}. Paste files are always marked {@code hidden}.
     * The filename is derived from the title with the extension {@code .md} for Markdown
     * syntax and {@code .txt} otherwise.
     *
     * @param title           paste title (used as the stored filename after sanitization)
     * @param content         paste body text
     * @param syntax          syntax hint: {@code "markdown"} or any other value for plain text
     * @param keepIndefinitely whether the paste should be exempt from scheduled deletion
     * @param password        optional access password
     * @param request         the HTTP request (provides requester metadata and admin session)
     * @return the saved {@link FileEntity}
     * @throws IOException if writing the paste to disk fails
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "analytics"}, allEntries = true)
    public FileEntity createPaste(String title,
                                  String content,
                                  String syntax,
                                  boolean keepIndefinitely,
                                  String password,
                                  HttpServletRequest request) throws IOException {
        PasteUploadOptions options = resolvePasteUploadOptions(keepIndefinitely, password, request);

        String fileName = sanitizePasteFileName(title, syntax);
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        validatePasteSize(contentBytes);
        RequesterInfo requesterInfo = getRequesterInfo(request);

        FileUploadRequest fileUploadRequest = new FileUploadRequest(
                null,
                options.keepIndefinitely(),
                options.password(),
                true,
                fileName,
                1,
                (long) contentBytes.length,
                requesterInfo.ipAddress(),
                requesterInfo.userAgent(),
                false,
                null,
                null,
                true
        );

        InMemoryMultipartFile multipartFile = new InMemoryMultipartFile(
                "file",
                fileName,
                "text/plain",
                contentBytes
        );

        FileEntity saved = asyncFileMergeService.submitChunk(fileUploadRequest, multipartFile, 0);
        if (saved != null) {
            fileHistoryLogRepository.save(new FileHistoryLog(saved, FileHistoryType.PASTE_CREATE, requesterInfo.ipAddress(), requesterInfo.userAgent()));
        }
        return saved;
    }

    /**
     * Overwrites the content of an existing paste.
     *
     * <p>The new content is written to a temporary file alongside the original, then
     * atomically replaced with {@link StandardCopyOption#REPLACE_EXISTING}. For encrypted
     * pastes the existing password from the session token is reused; the request is
     * rejected if the session is missing or the password cannot be retrieved.
     *
     * @param uuid            the paste UUID
     * @param title           new paste title (used to derive the filename)
     * @param content         new paste body text
     * @param syntax          syntax hint for filename extension
     * @param keepIndefinitely whether the paste should be exempt from scheduled deletion
     * @param request         the HTTP request (provides session token and admin check)
     * @return the updated {@link FileEntity}, or {@code null} if the UUID is not a paste
     * @throws IOException              if writing the new content fails
     * @throws IllegalArgumentException if the paste is encrypted but no valid session exists
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "analytics"}, allEntries = true)
    public FileEntity updatePaste(String uuid,
                                  String title,
                                  String content,
                                  String syntax,
                                  boolean keepIndefinitely,
                                  HttpServletRequest request) throws IOException {
        Optional<FileEntity> byUuid = fileRepository.findByUUID(uuid);
        if (byUuid.isEmpty()) {
            return null;
        }

        FileEntity fileEntity = byUuid.get();
        if (!fileEntity.paste) {
            return null;
        }

        PasteUploadOptions options = resolvePasteUploadOptions(keepIndefinitely, null, request);
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        validatePasteSize(contentBytes);
        String existingPassword = fileEntity.encrypted ? getFilePasswordFromSessionToken(request) : null;
        if (fileEntity.encrypted && (existingPassword == null || existingPassword.isBlank())) {
            throw new IllegalArgumentException("Valid paste session is required to edit encrypted pastes.");
        }

        Path storagePath = Path.of(applicationSettingsService.getFileStoragePath());
        Path filePath = storagePath.resolve(fileEntity.uuid);
        Path tempPath = storagePath.resolve(fileEntity.uuid + "-paste-tmp");

        Files.createDirectories(storagePath);
        writeContentToFile(tempPath, contentBytes, existingPassword);
        Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);

        fileEntity.name = sanitizePasteFileName(title, syntax);
        fileEntity.description = null;
        fileEntity.size = contentBytes.length;
        fileEntity.keepIndefinitely = options.keepIndefinitely();
        fileEntity.hidden = true;
        fileEntity.uploadDate = LocalDate.now();

        fileRepository.save(fileEntity);
        logHistory(fileEntity, request, FileHistoryType.PASTE_EDIT);
        return fileEntity;
    }

    /**
     * Reads and returns the full text content of a paste.
     *
     * <p>Decrypts the content if the paste is encrypted, using the password from the
     * current file session token. Returns {@code null} if the UUID is not found, does
     * not refer to a paste, or if an I/O error occurs.
     *
     * @param uuid    the paste UUID
     * @param request the HTTP request (provides session token for decryption)
     * @return paste content as a UTF-8 string, or {@code null} on failure
     */
    public String getPasteContent(String uuid, HttpServletRequest request) {
        Optional<FileEntity> byUuid = fileRepository.findByUUID(uuid);
        if (byUuid.isEmpty() || !byUuid.get().paste) {
            return null;
        }

        FileEntity fileEntity = byUuid.get();
        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        String password = getFilePasswordFromSessionToken(request);

        try (InputStream inputStream = fileEntity.encrypted
                ? fileEncryptionService.getDecryptedInputStream(filePath.toFile(), password)
                : new FileInputStream(filePath.toFile())) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Unable to read paste content for {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    /**
     * Writes byte content to a file, encrypting it if a password is provided and
     * encryption is enabled. Any pre-existing file at {@code outputPath} is deleted first.
     *
     * @param outputPath   destination file path
     * @param contentBytes raw content bytes
     * @param password     optional encryption password; {@code null} or blank writes plaintext
     * @throws IOException if writing fails
     */
    private void writeContentToFile(Path outputPath, byte[] contentBytes, String password) throws IOException {
        Files.deleteIfExists(outputPath);

        boolean encrypt = password != null && !password.isBlank() && applicationSettingsService.isEncryptionEnabled();
        if (!encrypt) {
            Files.write(outputPath, contentBytes);
            return;
        }

        try (OutputStream encryptedOut = fileEncryptionService.getEncryptedOutputStream(outputPath.toFile(), password)) {
            encryptedOut.write(contentBytes);
        } catch (Exception e) {
            throw new IOException("Failed to write encrypted paste", e);
        }
    }

    /**
     * Resolves the effective upload options for a paste based on admin session state
     * and global settings.
     *
     * <p>{@code keepIndefinitely} is only honoured if the setting is unrestricted or the
     * request carries an admin session. Passwords are suppressed entirely when upload
     * passwords are disabled in settings.
     *
     * @param keepIndefinitely requested keep-indefinitely flag
     * @param password         requested access password
     * @param request          the HTTP request (for admin session check)
     * @return resolved {@link PasteUploadOptions}
     */
    private PasteUploadOptions resolvePasteUploadOptions(boolean keepIndefinitely,
                                                         String password,
                                                         HttpServletRequest request) {
        boolean adminSession = sessionService.hasValidAdminSession(request);
        boolean allowKeepIndefinitely = !applicationSettingsService.isKeepIndefinitelyAdminOnly() || adminSession;
        boolean keepIndefinitelyValue = allowKeepIndefinitely && keepIndefinitely;
        boolean uploadPasswordEnabled = applicationSettingsService.isUploadPasswordEnabled();
        String effectivePassword = uploadPasswordEnabled ? password : null;
        return new PasteUploadOptions(keepIndefinitelyValue, effectivePassword);
    }

    /**
     * Validates that the paste content does not exceed the configured maximum file size.
     *
     * @param contentBytes paste content bytes
     * @throws IllegalArgumentException if the limit is exceeded
     */
    private void validatePasteSize(byte[] contentBytes) {
        if (contentBytes.length > applicationSettingsService.getMaxFileSize()) {
            throw new IllegalArgumentException("Paste exceeds max file size limit.");
        }
    }

    /**
     * Sanitizes a paste title and appends the appropriate extension based on syntax.
     *
     * <p>Special characters (anything other than alphanumerics, dots, spaces, underscores,
     * and hyphens) are replaced with underscores. Any trailing {@code .txt} or {@code .md}
     * extension in the title is stripped before the canonical extension is appended.
     *
     * @param title  paste title, or {@code null} / blank for a default name
     * @param syntax {@code "markdown"} for {@code .md}, anything else for {@code .txt}
     * @return sanitized filename with extension
     */
    private String sanitizePasteFileName(String title, String syntax) {
        String baseName = title == null || title.isBlank() ? "paste" : title.trim();
        String sanitized = baseName.replaceAll("[^a-zA-Z0-9._ -]", "_");
        String lower = sanitized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            sanitized = sanitized.substring(0, Math.max(0, sanitized.lastIndexOf('.')));
        }
        if (sanitized.isBlank()) {
            sanitized = "paste";
        }
        String extension = "markdown".equalsIgnoreCase(syntax) ? ".md" : ".txt";
        return sanitized + extension;
    }

    /**
     * Returns a {@link StreamingResponseBody} that streams the file associated with
     * a share token, decrementing the remaining download count and deleting the token
     * when exhausted.
     *
     * <p>New-style tokens (where {@link ShareTokenEntity#shareKeyHash} is non-null) have an
     * AES-encrypted sidecar at {@code {uuid}-share-{token}}. The share key is read from the
     * HTTP session (stored there by {@link org.rostislav.quickdrop.controller.ShareViewController}
     * after BCrypt verification) and used to decrypt the sidecar on-the-fly. If the sidecar
     * file is missing (e.g. already deleted after a prior exhausted download), the broken
     * token is removed via {@link org.rostislav.quickdrop.repository.ShareTokenRepository#deleteByIdTransactional}
     * and {@code null} is returned without logging a download event.
     *
     * <p>The {@link org.rostislav.quickdrop.model.FileHistoryType#SHARE_DOWNLOAD} history
     * entry is written only after confirming the sidecar exists, so failed attempts are
     * not recorded as downloads.
     *
     * <p>Legacy tokens ({@code shareKeyHash == null}) fall back to streaming a plaintext
     * {@code {uuid}-decrypted} sidecar if one exists, or the raw file otherwise.
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

        FileEntity fileEntity = shareTokenEntity.file;
        String storagePath = applicationSettingsService.getFileStoragePath();

        if (shareTokenEntity.shareKeyHash != null) {
            Path sidecarPath = Path.of(storagePath, fileEntity.uuid + "-share-" + shareTokenEntity.shareToken);
            if (!Files.exists(sidecarPath)) {
                logger.warn("Sidecar missing for token {}, deleting broken token", shareTokenEntity.shareToken);
                shareTokenRepository.deleteByIdTransactional(shareTokenEntity.getId());
                return null;
            }
            logHistory(fileEntity, request, FileHistoryType.SHARE_DOWNLOAD);
            String shareKey = (String) request.getSession().getAttribute("share-key-" + shareTokenEntity.shareToken);
            return outputStream -> {
                try {
                    InputStream decIn;
                    try {
                        decIn = fileEncryptionService.getDecryptedInputStream(sidecarPath.toFile(), shareKey);
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
                    updateShareTokenAfterDownload(shareTokenEntity, fileEntity);
                }
            };
        } else {
            // Legacy path: stream plaintext sidecar if it exists, otherwise raw file
            logHistory(fileEntity, request, FileHistoryType.SHARE_DOWNLOAD);
            Path decryptedFilePath = Path.of(storagePath, fileEntity.uuid + "-decrypted");
            Path filePathToStream = Files.exists(decryptedFilePath)
                    ? decryptedFilePath
                    : Path.of(storagePath, fileEntity.uuid);
            return outputStream -> {
                try {
                    streamFile(filePathToStream, decryptedFilePath, fileEntity.uuid, outputStream);
                } finally {
                    updateShareTokenAfterDownload(shareTokenEntity, fileEntity);
                }
            };
        }
    }

    /**
     * Decrements the remaining download count on a share token after a successful
     * download and deletes the token (and its sidecar) if it is now exhausted or expired.
     * Deletion uses {@link org.rostislav.quickdrop.repository.ShareTokenRepository#deleteByIdTransactional}
     * (a targeted {@code @Modifying @Transactional} JPQL DELETE) because this method runs
     * inside the {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody}
     * lambda on Spring's async streaming thread, where the JPA persistence context from
     * the original request thread is already closed.
     *
     * @param shareTokenEntity the share token to update
     * @param fileEntity       the file that was streamed (used only for logging)
     */
    private void updateShareTokenAfterDownload(ShareTokenEntity shareTokenEntity, FileEntity fileEntity) {
        if (shareTokenEntity.numberOfAllowedDownloads != null) {
            shareTokenEntity.numberOfAllowedDownloads--;
        }

        if (!validateShareToken(shareTokenEntity)) {
            deleteShareSidecar(shareTokenEntity);
            shareTokenRepository.deleteByIdTransactional(shareTokenEntity.getId());
        } else {
            shareTokenRepository.save(shareTokenEntity);
        }
        logger.info("Share token updated/invalidated. File streamed successfully: {}", fileEntity.name);
    }

    /**
     * Deletes the re-encrypted sidecar file for a share token, if one exists.
     * Only acts on new-style tokens ({@link ShareTokenEntity#shareKeyHash} non-null).
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
     * Updates the {@code keepIndefinitely} flag on a file.
     *
     * <p>When the flag is cleared (set to {@code false}) the file's upload date is
     * also reset to today via {@link #extendFile}, restarting the deletion countdown.
     * If the {@code keepIndefinitelyAdminOnly} setting is active, non-admin requests
     * are silently rejected and the unchanged entity is returned.
     *
     * @param uuid             the file UUID
     * @param keepIndefinitely the new flag value
     * @param request          the HTTP request (for admin session check and history logging)
     * @return the (possibly updated) {@link FileEntity}, or {@code null} if not found
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
    public FileEntity updateKeepIndefinitely(String uuid, boolean keepIndefinitely, HttpServletRequest request) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            logger.info("File not found for 'update keep indefinitely': {}", uuid);
            return null;
        }

        if (applicationSettingsService.isKeepIndefinitelyAdminOnly() && !sessionService.hasValidAdminSession(request)) {
            logger.info("Keep indefinitely change blocked (admin only) for file UUID: {}", uuid);
            return referenceById.get();
        }

        if (!keepIndefinitely) {
            extendFile(uuid, request);
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.keepIndefinitely = keepIndefinitely;
        logger.info("File keepIndefinitely updated: {}", fileEntity);
        fileRepository.save(fileEntity);
        return fileEntity;
    }

    /**
     * Saves a history log entry and sends a notification for a file event.
     *
     * @param fileEntity the file that triggered the event
     * @param request    the HTTP request providing requester metadata
     * @param eventType  the event type to record
     */
    private void logHistory(FileEntity fileEntity, HttpServletRequest request, FileHistoryType eventType) {
        RequesterInfo info = getRequesterInfo(request);
        fileHistoryLogRepository.save(new FileHistoryLog(fileEntity, eventType, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(fileEntity, eventType);
    }

    /**
     * Generates a unique share token string for the given file, retrying on collision.
     *
     * @param fileEntity the file to generate a token for
     * @return a collision-free token string
     */
    private String generateUniqueShareToken(FileEntity fileEntity) {
        String token;
        do {
            token = generateHashedToken(fileEntity);
        } while (shareTokenRepository.existsByShareToken(token));
        return token;
    }

    /**
     * Looks up an existing unlimited (no expiry, no download limit) share token for a file.
     *
     * @param file the file entity
     * @return an existing unlimited token if one exists
     */
    private Optional<ShareTokenEntity> findUnlimitedShareToken(FileEntity file) {
        return shareTokenRepository.findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(file);
    }

    /**
     * Generates (or returns an existing) share token for a non-encrypted file.
     *
     * <p>If both {@code tokenExpirationDate} and {@code numberOfDownloads} are {@code null}
     * and an unlimited token already exists for the file, it is returned without creating
     * a new one.
     *
     * @param uuid                 the file UUID
     * @param tokenExpirationDate  optional expiry date for the token
     * @param numberOfDownloads    optional download limit; {@code null} means unlimited
     * @return the new or existing {@link ShareTokenEntity}
     * @throws IllegalArgumentException if the UUID is not found
     */
    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }
        FileEntity file = optionalFile.get();

        if (tokenExpirationDate == null && numberOfDownloads == null) {
            Optional<ShareTokenEntity> existing = findUnlimitedShareToken(file);
            if (existing.isPresent()) {
                ShareTokenEntity token = existing.get();
                token.createdAt = LocalDateTime.now();
                return shareTokenRepository.save(token);
            }
        }

        String token = generateUniqueShareToken(file);
        ShareTokenEntity shareToken = new ShareTokenEntity(token, file, tokenExpirationDate, numberOfDownloads);
        shareTokenRepository.save(shareToken);

        return shareToken;
    }

    /**
     * Generates a share token for a password-protected file.
     *
     * <p>For encrypted files a randomly generated share key is BCrypt-hashed and stored
     * in the token; the plaintext key is returned so the caller can embed it in the share
     * URL. Sidecar re-encryption (decrypt original → re-encrypt with share key) is
     * submitted to {@link ShareEncryptionService} and runs in the background, so this
     * method returns immediately. The returned token will have
     * {@link ShareTokenEntity#sidecarReady} set to {@code false}; callers should surface
     * this to the creator and recipients accordingly. No plaintext copy ever touches disk.
     *
     * <p>For files with a password but encryption disabled the non-encrypted 3-param
     * overload handles token creation (reusing unlimited tokens when applicable) and this
     * method wraps the result with a {@code null} share key and {@code sidecarReady = true}.
     *
     * @param uuid                the file UUID
     * @param tokenExpirationDate optional expiry date
     * @param sessionToken        file session token (provides the decryption password for encrypted files)
     * @param numberOfDownloads   optional download limit
     * @return a result holding the persisted {@link ShareTokenEntity} and the plaintext share key
     *         (the key is {@code null} when the file is not AES-encrypted)
     * @throws IllegalArgumentException if the UUID is not found
     */
    public ShareTokenResult generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, Integer numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        FileEntity file = optionalFile.get();

        if (!file.encrypted) {
            // Non-encrypted but password-protected: delegate to the plain overload
            ShareTokenEntity shareToken = generateShareToken(uuid, tokenExpirationDate, numberOfDownloads);
            return new ShareTokenResult(shareToken, null);
        }

        // Encrypted: generate a fresh token and kick off sidecar re-encryption in the background
        String shareKey = java.util.UUID.randomUUID().toString();
        String token = generateUniqueShareToken(file);

        // Pre-fetch the password on the request thread before the async task runs,
        // so the HTTP session is not accessed from a background thread.
        String plainPassword = sessionService.getPasswordForFileSessionToken(sessionToken).getPassword();

        ShareTokenEntity shareToken = new ShareTokenEntity(token, file, tokenExpirationDate, numberOfDownloads);
        shareToken.shareKeyHash = passwordEncoder.encode(shareKey);
        shareToken.sidecarReady = false;
        shareTokenRepository.save(shareToken);

        shareEncryptionService.encryptSidecarAsync(file.uuid, token, shareKey, plainPassword,
                shareToken.getId(), shareTokenRepository);

        logger.info("Share token saved; sidecar encryption submitted in background for file: {}", file.name);
        return new ShareTokenResult(shareToken, shareKey);
    }

    /**
     * Looks up a share token entity by its token string.
     *
     * @param token the share token string
     * @return the matching {@link ShareTokenEntity}, or empty if not found
     */
    public Optional<ShareTokenEntity> getShareTokenEntityByToken(String token) {
        return shareTokenRepository.findByShareToken(token);
    }

    /**
     * Records a {@link FileHistoryType#SHARE_CREATE} log entry for the given file.
     *
     * <p>Called from the controller layer after a share token is successfully generated
     * so that the requester's real IP address and user-agent are captured from the
     * HTTP request.
     *
     * @param file    the file for which a share token was created
     * @param request the HTTP request that triggered token generation
     */
    public void logShareCreate(FileEntity file, HttpServletRequest request) {
        logHistory(file, request, FileHistoryType.SHARE_CREATE);
    }

    /**
     * Revokes a share token by ID: deletes its sidecar file if present, logs a
     * {@link FileHistoryType#SHARE_REVOKE} event against the associated file, and
     * removes the token row. Does nothing if the token does not exist.
     *
     * @param tokenId the database ID of the token to revoke
     * @param request the HTTP request used for history-log IP/user-agent metadata
     */
    public void revokeShareToken(Long tokenId, HttpServletRequest request) {
        shareTokenRepository.findById(tokenId).ifPresent(token -> {
            deleteShareSidecar(token);
            if (token.file != null) {
                logHistory(token.file, request, FileHistoryType.SHARE_REVOKE);
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
     * @param fileEntity  the file metadata
     * @param request     the HTTP request (for history logging)
     * @return a {@code 200 OK} streaming response
     */
    private ResponseEntity<StreamingResponseBody> createFileDownloadResponse(InputStream inputStream, FileEntity fileEntity, HttpServletRequest request) {
        StreamingResponseBody responseBody = getStreamingResponseBody(inputStream);
        logger.info("Sending file: {}", fileEntity);
        logHistory(fileEntity, request, FileHistoryType.DOWNLOAD);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + URLEncoder.encode(fileEntity.name, StandardCharsets.UTF_8) + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileEntity.size))
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
     * Returns {@code true} if the upload request should result in an encrypted file on disk.
     * Encryption requires a non-blank password and that encryption is enabled in settings.
     *
     * @param request the upload request
     * @return {@code true} if the file should be AES-encrypted
     */
    public boolean shouldEncrypt(FileUploadRequest request) {
        return request.password != null && !request.password.isBlank() && applicationSettingsService.isEncryptionEnabled();
    }

    /**
     * Immutable holder for the IP address and user-agent string of a requester.
     *
     * @param ipAddress  client IP address
     * @param userAgent  HTTP {@code User-Agent} header value
     */
    public record RequesterInfo(String ipAddress, String userAgent) {
    }

    /**
     * Resolved options for a paste upload after admin and settings checks have been applied.
     *
     * @param keepIndefinitely effective keep-indefinitely flag
     * @param password         effective access password (may be {@code null} if disabled)
     */
    private record PasteUploadOptions(boolean keepIndefinitely, String password) {
    }

    /**
     * Holds the result of generating a share token for a password-protected file.
     *
     * @param token    the persisted share token entity
     * @param shareKey the plaintext share key to embed in the URL, or {@code null} for non-encrypted files
     */
    public record ShareTokenResult(ShareTokenEntity token, String shareKey) {
    }
}
