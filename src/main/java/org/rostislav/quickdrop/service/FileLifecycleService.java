package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.*;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.RequesterInfo;
import org.rostislav.quickdrop.model.ShortLinkResult;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.rostislav.quickdrop.storage.StorageService;

import java.time.LocalDate;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileUtils.getRequesterInfo;

/**
 * Write service for file and paste lifecycle — creation, soft-deletion, field updates,
 * and share-token management.
 *
 * <p>Most mutating methods carry {@link CacheEvict} annotations to keep the read-side
 * caches (populated by {@link FileQueryService}) consistent after writes.
 * Audit events ({@link org.rostislav.quickdrop.entity.ActivityLog}) and notifications
 * are fired for every significant state change.
 */
@Service
public class FileLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(FileLifecycleService.class);

    private final UploadRepository uploadRepository;
    private final ActivityLogRepository activityLogRepository;
    private final NotificationService notificationService;
    private final ShortLinkService shortLinkService;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;
    private final FileDownloadService fileDownloadService;
    private final PasswordEncoder passwordEncoder;
    private final StorageService storageService;

    public FileLifecycleService(UploadRepository uploadRepository,
                                ActivityLogRepository activityLogRepository,
                                NotificationService notificationService,
                                ShortLinkService shortLinkService,
                                ApplicationSettingsService applicationSettingsService,
                                SessionService sessionService,
                                FileDownloadService fileDownloadService,
                                PasswordEncoder passwordEncoder,
                                StorageService storageService) {
        this.uploadRepository = uploadRepository;
        this.activityLogRepository = activityLogRepository;
        this.notificationService = notificationService;
        this.shortLinkService = shortLinkService;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
        this.fileDownloadService = fileDownloadService;
        this.passwordEncoder = passwordEncoder;
        this.storageService = storageService;
    }

    /**
     * Deletes the file at the storage path identified by {@code uuid}.
     *
     * <p>A missing file is treated as a successful deletion (idempotent) so callers do
     * not need to check existence beforehand.
     *
     * @return {@code true} if the file was deleted or was already absent; {@code false} on I/O error
     */
    public boolean deleteFileFromFileSystem(String uuid) {
        return storageService.delete(uuid);
    }

    /**
     * Deletes the file from both the filesystem and the database (soft-delete) in one call.
     *
     * <p>The filesystem deletion is attempted first; if it fails, the database record is left
     * intact and {@code false} is returned so the caller can surface the error to the user.
     *
     * @return {@code true} if both the filesystem and database operations succeeded
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
     * Soft-deletes the upload identified by {@code uuid} without recording requester info
     * in the audit log. Used by scheduled cleanup jobs where no HTTP context is available.
     *
     * @see #removeFileFromDatabase(String, String, String)
     */
    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid) {
        return removeFileFromDatabase(uuid, null, null);
    }

    /**
     * Soft-deletes the upload identified by {@code uuid}, writes a deletion audit log entry,
     * fires a deletion notification, and purges all share tokens (plus any sidecars) for the file.
     *
     * <p>If the record is already soft-deleted this is a no-op that returns {@code true}.
     *
     * @param ipAddress requester IP for the audit log; may be {@code null}
     * @param userAgent requester User-Agent for the audit log; may be {@code null}
     * @return {@code true} if the file was found (and soft-deleted or already deleted)
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
            return true;
        }

        notificationService.notifyFileAction(upload, EventType.DELETION);
        activityLogRepository.save(new ActivityLog(upload, EventType.DELETION, ipAddress, userAgent));

        shortLinkService.purgeLinksForUpload(upload);

        upload.deleted = true;
        uploadRepository.save(upload);
        return true;
    }

    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public void logDownload(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) return;
        RequesterInfo info = getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        activityLogRepository.save(new ActivityLog(upload, EventType.DOWNLOAD, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(upload, EventType.DOWNLOAD);
    }

    @CacheEvict(value = {"publicFiles", "adminFiles", "adminPastes", "adminDeletedPastes"}, allEntries = true)
    public void extendFile(String uuid, HttpServletRequest request) {
        Optional<Upload> referenceById = uploadRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return;
        }
        Upload upload = referenceById.get();
        if (!canMutateNoPasswordUpload(upload, request)) {
            logger.info("Extend blocked (no credential for a no-password upload) for UUID: {}", uuid);
            return;
        }
        upload.uploadDate = LocalDate.now();
        logger.info("Upload extended: {}", upload);
        uploadRepository.save(upload);
        logHistory(upload, request, EventType.RENEWAL);
    }

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
        if (!canMutateNoPasswordUpload(upload, request)) {
            logger.info("Hide toggle blocked (no credential for a no-password upload) for UUID: {}", uuid);
            return upload;
        }

        upload.hidden = !upload.hidden;
        logger.info("Upload hidden updated: {}", upload);
        uploadRepository.save(upload);
        return upload;
    }

    /**
     * Returns {@code true} when the caller may mutate (extend/keep-indefinitely/hide) an
     * upload that has <em>no password set</em> — an admin session, or nobody else, since a
     * no-password upload has no credential to check a session token against. This matches
     * the precedent {@link org.rostislav.quickdrop.controller.FileViewController}'s
     * delete-authorization check and {@link FileQueryService#isAuthorizedToEdit} already set
     * for the same "no password = no owner proof" case, and is exactly what the fileView.html
     * template's existing {@code keepDisabled}/{@code hiddenDisabled} logic already visually
     * enforces — this brings the server in line with what the UI already implied.
     *
     * <p>Password-protected uploads are unaffected and always return {@code true} here:
     * {@link org.rostislav.quickdrop.interceptor.FilePasswordInterceptor} already gates every
     * {@code /file/**} request for them before the controller runs, so by the time this method
     * is reached (from a {@code /file/**} route) the caller has already proven a valid
     * file-session token. {@code /admin/**} routes are separately gated by
     * {@code AdminPasswordInterceptor}, so the admin-session check below is trivially satisfied
     * there too.
     */
    private boolean canMutateNoPasswordUpload(Upload upload, HttpServletRequest request) {
        if (upload.passwordHash != null && !upload.passwordHash.isBlank()) {
            return true;
        }
        return request != null && sessionService.hasValidAdminSession(request);
    }

    /**
     * Sets the {@code keepIndefinitely} flag for the file identified by {@code uuid}.
     *
     * <p>When {@code keepIndefinitely} is {@code false}, the upload date is reset to today
     * via {@link #extendFile} so the file begins a fresh retention window rather than
     * expiring immediately if it was already old.
     *
     * <p>Blocked when the setting is admin-only and the request lacks a valid admin session;
     * in that case the unchanged upload is returned.
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
        if (!canMutateNoPasswordUpload(referenceById.get(), request)) {
            logger.info("Keep indefinitely change blocked (no credential for a no-password upload) for UUID: {}", uuid);
            return referenceById.get();
        }

        if (!keepIndefinitely) {
            extendFile(uuid, request);
        }

        // Re-fetch after extendFile so we don't overwrite the uploadDate it just set.
        Upload upload = uploadRepository.findByUUID(uuid).orElseGet(referenceById::get);
        upload.keepIndefinitely = keepIndefinitely;
        logger.info("Upload keepIndefinitely updated: {}", upload);
        uploadRepository.save(upload);
        return upload;
    }

    /**
     * Records a {@link EventType#SHARE_CREATE} event for audit and notification purposes.
     */
    public void logShareCreate(Upload upload, HttpServletRequest request) {
        logHistory(upload, request, EventType.SHARE_CREATE);
    }

    /**
     * Revokes the share token with the given ID: deletes its sidecar file (if present),
     * fires a {@link EventType#SHARE_REVOKE} audit event, and removes the token record.
     */
    public void revokeShareToken(Long tokenId, HttpServletRequest request) {
        shortLinkService.revokeUploadLink(tokenId)
                .ifPresent(upload -> logHistory(upload, request, EventType.SHARE_REVOKE));
    }

    void logHistory(Upload upload, HttpServletRequest request, EventType eventType) {
        RequesterInfo info = getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        activityLogRepository.save(new ActivityLog(upload, eventType, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(upload, eventType);
    }

    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public Upload saveFile(UploadRequest uploadRequest, String uuid) {
        if (!validateObjects(uploadRequest)) {
            return null;
        }
        logger.info("Saving file: {}", uploadRequest.fileName);
        Upload upload = populateUpload(uploadRequest, uuid);
        logger.info("Upload inserted into database: {}", upload);
        Upload saved = uploadRepository.save(upload);
        activityLogRepository.save(new ActivityLog(saved, EventType.UPLOAD, uploadRequest.uploaderIp, uploadRequest.uploaderUserAgent));
        notificationService.notifyFileAction(saved, EventType.UPLOAD);
        return saved;
    }

    /**
     * Creates (or returns an existing) share token for the file identified by {@code uuid}.
     *
     * <p>Delegates to {@link ShortLinkService#createUploadLink}; kept as a thin wrapper here
     * (same name and signature as before the short-link/URL-shortener merge) so every
     * existing caller of this method continues to work unchanged.
     *
     * @throws IllegalArgumentException if no file with the given UUID exists
     */
    public UploadShareLink generateShareToken(String uuid, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        return shortLinkService.createUploadLink(uuid, tokenExpirationDate, numberOfDownloads);
    }

    /**
     * Creates a share token for an AES-encrypted file, then kicks off background
     * re-encryption of a sidecar copy keyed with a fresh per-share key.
     *
     * <p>Delegates to {@link ShortLinkService#createEncryptedUploadLink}; kept as a thin
     * wrapper here for the same reason as the overload above.
     *
     * @param sessionToken the file-session token used to retrieve the encryption password
     * @return a {@link ShortLinkResult} containing the new token and the per-share key;
     *         the key must be embedded in the share URL so the recipient can decrypt the sidecar
     * @throws IllegalArgumentException if no file with the given UUID exists
     */
    public ShortLinkResult generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, Integer numberOfDownloads) {
        return shortLinkService.createEncryptedUploadLink(uuid, tokenExpirationDate, sessionToken, numberOfDownloads);
    }

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
        upload.encrypted = request.password != null && !request.password.isBlank()
                && applicationSettingsService.isEncryptionEnabled()
                && !request.editOnly;
        if (request.password != null && !request.password.isBlank()) {
            upload.passwordHash = passwordEncoder.encode(request.password);
        }
        return upload;
    }
}
