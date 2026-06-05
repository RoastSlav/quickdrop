package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.PasteEntityView;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.PasteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.rostislav.quickdrop.model.RequesterInfo;
import static org.rostislav.quickdrop.util.FileUtils.getRequesterInfo;

/**
 * Service for all paste lifecycle operations: creation, editing, content retrieval,
 * view logging, and paginated admin/public listings.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Paste creation via the chunked-upload pipeline ({@link #createPaste}).</li>
 *   <li>Overwriting paste content ({@link #updatePaste}).</li>
 *   <li>Reading paste content with optional AES decryption ({@link #getPasteContent}).</li>
 *   <li>Logging paste view events ({@link #logPasteView}).</li>
 *   <li>Paginated paste listings for the admin UI ({@link #getPaginatedPastes},
 *       {@link #getDeletedPaginatedPastes}).</li>
 *   <li>Paste-specific analytics aggregates ({@link #getPasteCount},
 *       {@link #getAveragePasteLength}, {@link #getMarkdownPasteCount}).</li>
 * </ul>
 */
@Service
public class PasteService {
    private static final Logger logger = LoggerFactory.getLogger(PasteService.class);

    private final PasteRepository pasteRepository;
    private final ActivityLogRepository activityLogRepository;
    private final ApplicationSettingsService applicationSettingsService;
    private final EncryptionService encryptionService;
    private final SessionService sessionService;
    private final AsyncFileMergeService asyncFileMergeService;

    @Lazy
    public PasteService(PasteRepository pasteRepository,
                        ActivityLogRepository activityLogRepository,
                        ApplicationSettingsService applicationSettingsService,
                        EncryptionService encryptionService,
                        SessionService sessionService,
                        @Lazy AsyncFileMergeService asyncFileMergeService) {
        this.pasteRepository = pasteRepository;
        this.activityLogRepository = activityLogRepository;
        this.applicationSettingsService = applicationSettingsService;
        this.encryptionService = encryptionService;
        this.sessionService = sessionService;
        this.asyncFileMergeService = asyncFileMergeService;
    }

    // -------------------------------------------------------------------------
    // Analytics aggregates
    // -------------------------------------------------------------------------

    /**
     * @return number of live (non-deleted) paste entries
     */
    public long getPasteCount() {
        return pasteRepository.countPastes();
    }

    /**
     * Returns the average size of paste content in bytes.
     *
     * @return average paste size, or {@code 0.0} if there are no pastes
     */
    public double getAveragePasteLength() {
        Double avg = pasteRepository.averagePasteLength();
        return avg != null ? avg : 0.0;
    }

    /**
     * @return number of paste entries whose filename ends with {@code .md}
     */
    public long getMarkdownPasteCount() {
        return pasteRepository.countMarkdownPastes();
    }

    // -------------------------------------------------------------------------
    // Paginated listings
    // -------------------------------------------------------------------------

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
            return pasteRepository.findPastesWithViewCounts(pageable);
        }
        return pasteRepository.searchPastesWithViewCounts(query, pageable);
    }

    /**
     * Returns a paginated list of soft-deleted pastes with pre-aggregated view counts,
     * optionally filtered by a search query. Results are cached per page/size/query combination.
     *
     * @param pageable pagination parameters
     * @param query    optional search string
     * @return a page of {@link PasteEntityView} projections for deleted pastes
     */
    @Cacheable(value = "adminDeletedPastes", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<PasteEntityView> getDeletedPaginatedPastes(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return pasteRepository.findDeletedPastesWithViewCounts(pageable);
        }
        return pasteRepository.searchDeletedPastesWithViewCounts(query, pageable);
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /**
     * Creates a new paste from the provided title, content, and syntax hint.
     *
     * <p>The resulting entity is marked {@code hidden}.
     *
     * @param title            paste title (used as the stored filename after sanitization)
     * @param content          paste body text
     * @param syntax           syntax hint: {@code "markdown"} or any other value for plain text
     * @param keepIndefinitely whether the paste should be exempt from scheduled deletion
     * @param password         optional access password (cleared when upload passwords are disabled)
     * @param immutable        whether the paste should be permanently immutable after creation
     * @param editOnly         when {@code true} the password guards editing only; viewing is public
     *                         (also suppresses AES encryption so content can be served without a key)
     * @param request          the HTTP request (provides requester metadata and admin session)
     * @return the saved {@link Upload} (a {@link Paste} instance), or {@code null} on failure
     * @throws IOException if writing the paste to disk fails
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public Upload createPaste(String title,
                              String content,
                              String syntax,
                              boolean keepIndefinitely,
                              String password,
                              boolean immutable,
                              boolean editOnly,
                              HttpServletRequest request) throws IOException {
        PasteUploadOptions options = resolvePasteUploadOptions(keepIndefinitely, password, request);

        // editOnly is only relevant when a password is actually set; if passwords are disabled
        // or no password was provided, the flag has no effect and we normalise it to false.
        boolean effectiveEditOnly = editOnly && options.password() != null && !options.password().isBlank();

        String fileName = sanitizePasteFileName(title, syntax);
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        validatePasteSize(contentBytes);
        RequesterInfo requesterInfo = getRequesterInfo(request);

        UploadRequest uploadRequest = new UploadRequest(
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
                true,
                effectiveEditOnly,
                immutable
        );
        uploadRequest.uploadId = UUID.randomUUID().toString();

        org.rostislav.quickdrop.model.InMemoryMultipartFile inMemoryFile =
                new org.rostislav.quickdrop.model.InMemoryMultipartFile(
                        "file", fileName, "text/plain", contentBytes);

        Upload saved = asyncFileMergeService.submitChunk(uploadRequest, inMemoryFile, 0);
        if (saved != null) {
            activityLogRepository.save(new ActivityLog(saved, EventType.PASTE_CREATE,
                    requesterInfo.ipAddress(), requesterInfo.userAgent()));
        }
        return saved;
    }

    /**
     * Overwrites the content of an existing paste.
     *
     * <p>Returns {@code null} if the UUID does not refer to a paste or if the paste is
     * already immutable. Throws {@link IllegalArgumentException} if the paste is encrypted
     * but no valid session exists.
     *
     * @param uuid             the paste UUID
     * @param title            new paste title (used to derive the filename)
     * @param content          new paste body text
     * @param syntax           syntax hint for filename extension
     * @param keepIndefinitely whether the paste should be exempt from scheduled deletion
     * @param setImmutable     when {@code true} the paste is locked permanently after this edit
     * @param request          the HTTP request (provides session token and admin check)
     * @return the updated {@link Paste}, or {@code null} if the UUID is not a paste or is immutable
     * @throws IOException              if writing the new content fails
     * @throws IllegalArgumentException if the paste is encrypted but no valid session exists
     */
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public Paste updatePaste(String uuid,
                             String title,
                             String content,
                             String syntax,
                             boolean keepIndefinitely,
                             boolean setImmutable,
                             HttpServletRequest request) throws IOException {
        Optional<Paste> byUuid = pasteRepository.findByUUID(uuid);
        if (byUuid.isEmpty()) {
            return null;
        }

        Paste paste = byUuid.get();
        if (paste.immutable) {
            logger.warn("Attempted to edit immutable paste: {}", uuid);
            return null;
        }

        PasteUploadOptions options = resolvePasteUploadOptions(keepIndefinitely, null, request);
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        validatePasteSize(contentBytes);
        String existingPassword = paste.encrypted ? getFilePasswordFromSessionToken(request) : null;
        if (paste.encrypted && (existingPassword == null || existingPassword.isBlank())) {
            throw new IllegalArgumentException("Valid paste session is required to edit encrypted pastes.");
        }

        Path storagePath = Path.of(applicationSettingsService.getFileStoragePath());
        Path filePath = storagePath.resolve(paste.uuid);
        Path tempPath = storagePath.resolve(paste.uuid + "-paste-tmp");

        Files.createDirectories(storagePath);
        writeContentToFile(tempPath, contentBytes, existingPassword);
        Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);

        paste.name = sanitizePasteFileName(title, syntax);
        paste.description = null;
        paste.size = contentBytes.length;
        paste.keepIndefinitely = options.keepIndefinitely();
        paste.hidden = true;
        paste.uploadDate = LocalDate.now();
        if (setImmutable) {
            paste.immutable = true; // ratchet: can set but never unset via this path
        }

        pasteRepository.save(paste);

        RequesterInfo info = getRequesterInfo(request);
        activityLogRepository.save(new ActivityLog(paste, EventType.PASTE_EDIT, info.ipAddress(), info.userAgent()));
        return paste;
    }

    /**
     * Reads and returns the full text content of a paste.
     *
     * <p>Decrypts the content if the paste is encrypted. Returns {@code null} if the
     * UUID is not found, does not refer to a paste, or if an I/O error occurs.
     *
     * @param uuid    the paste UUID
     * @param request the HTTP request (provides session token for decryption)
     * @return paste content as a UTF-8 string, or {@code null} on failure
     */
    public String getPasteContent(String uuid, HttpServletRequest request) {
        Optional<Paste> byUuid = pasteRepository.findByUUID(uuid);
        if (byUuid.isEmpty()) {
            return null;
        }

        Paste paste = byUuid.get();
        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), paste.uuid);
        String password = getFilePasswordFromSessionToken(request);

        try (InputStream inputStream = paste.encrypted
                ? encryptionService.getDecryptedInputStream(filePath.toFile(), password)
                : new FileInputStream(filePath.toFile())) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Unable to read paste content for {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    /**
     * Logs a {@link EventType#PASTE_VIEW} event for a paste.
     * Does nothing if the UUID is not found.
     *
     * @param uuid    the paste UUID
     * @param request the HTTP request providing requester metadata
     */
    @CacheEvict(value = {"adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public void logPasteView(String uuid, HttpServletRequest request) {
        Paste paste = pasteRepository.findByUUID(uuid).orElse(null);
        if (paste == null) return;
        RequesterInfo info = getRequesterInfo(request);
        activityLogRepository.save(new ActivityLog(paste, EventType.PASTE_VIEW, info.ipAddress(), info.userAgent()));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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
        org.rostislav.quickdrop.model.FileSession fileSession =
                sessionService.getPasswordForFileSessionToken(sessionToken.toString());
        return fileSession == null ? null : fileSession.getPassword();
    }

    /**
     * Resolves the effective upload options for a paste based on admin session state
     * and global settings.
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
        try (OutputStream encryptedOut = encryptionService.getEncryptedOutputStream(outputPath.toFile(), password)) {
            encryptedOut.write(contentBytes);
        } catch (Exception e) {
            throw new IOException("Failed to write encrypted paste", e);
        }
    }

    /**
     * Resolved options for a paste upload after admin and settings checks have been applied.
     *
     * @param keepIndefinitely effective keep-indefinitely flag
     * @param password         effective access password (may be {@code null} if disabled)
     */
    private record PasteUploadOptions(boolean keepIndefinitely, String password) {
    }
}
