package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.*;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.RequesterInfo;
import org.rostislav.quickdrop.model.ShareTokenResult;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileUtils.generateHashedToken;
import static org.rostislav.quickdrop.util.FileUtils.getRequesterInfo;

@Service
public class FileLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(FileLifecycleService.class);

    private final UploadRepository uploadRepository;
    private final ActivityLogRepository activityLogRepository;
    private final NotificationService notificationService;
    private final ShareTokenRepository shareTokenRepository;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;
    private final FileDownloadService fileDownloadService;
    private final PasswordEncoder passwordEncoder;
    private final ShareEncryptionService shareEncryptionService;

    public FileLifecycleService(UploadRepository uploadRepository,
                                ActivityLogRepository activityLogRepository,
                                NotificationService notificationService,
                                ShareTokenRepository shareTokenRepository,
                                ApplicationSettingsService applicationSettingsService,
                                SessionService sessionService,
                                FileDownloadService fileDownloadService,
                                PasswordEncoder passwordEncoder,
                                ShareEncryptionService shareEncryptionService) {
        this.uploadRepository = uploadRepository;
        this.activityLogRepository = activityLogRepository;
        this.notificationService = notificationService;
        this.shareTokenRepository = shareTokenRepository;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
        this.fileDownloadService = fileDownloadService;
        this.passwordEncoder = passwordEncoder;
        this.shareEncryptionService = shareEncryptionService;
    }

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

    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "adminDeletedFiles", "adminPastes", "adminDeletedPastes", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid) {
        return removeFileFromDatabase(uuid, null, null);
    }

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

        shareTokenRepository.findAllByFile(upload).forEach(fileDownloadService::deleteShareSidecar);
        shareTokenRepository.deleteAllByFile(upload);

        upload.deleted = true;
        uploadRepository.save(upload);
        return true;
    }

    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public void logDownload(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) return;
        RequesterInfo info = getRequesterInfo(request);
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

        upload.hidden = !upload.hidden;
        logger.info("Upload hidden updated: {}", upload);
        uploadRepository.save(upload);
        return upload;
    }

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

    public void logShareCreate(Upload upload, HttpServletRequest request) {
        logHistory(upload, request, EventType.SHARE_CREATE);
    }

    public void revokeShareToken(Long tokenId, HttpServletRequest request) {
        shareTokenRepository.findById(tokenId).ifPresent(token -> {
            fileDownloadService.deleteShareSidecar(token);
            if (token.file != null) {
                logHistory(token.file, request, EventType.SHARE_REVOKE);
            }
            shareTokenRepository.delete(token);
            logger.info("Share token {} revoked by admin", token.shareToken);
        });
    }

    void logHistory(Upload upload, HttpServletRequest request, EventType eventType) {
        RequesterInfo info = getRequesterInfo(request);
        activityLogRepository.save(new ActivityLog(upload, eventType, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(upload, eventType);
    }

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

    public ShareTokenResult generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, Integer numberOfDownloads) {
        Optional<Upload> optionalUpload = uploadRepository.findByUUID(uuid);
        if (optionalUpload.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }
        Upload upload = optionalUpload.get();

        if (!upload.encrypted) {
            ShareTokenEntity shareToken = generateShareToken(uuid, tokenExpirationDate, numberOfDownloads);
            return new ShareTokenResult(shareToken, null);
        }

        String shareKey = java.util.UUID.randomUUID().toString();
        String token = generateUniqueShareToken(upload);

        // Pre-fetch the password on the request thread — HTTP session must not be
        // accessed from the background encryption thread.
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

    private String generateUniqueShareToken(Upload upload) {
        String token;
        do {
            token = generateHashedToken(upload);
        } while (shareTokenRepository.existsByShareToken(token));
        return token;
    }

    private Optional<ShareTokenEntity> findUnlimitedShareToken(Upload upload) {
        return shareTokenRepository.findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(upload);
    }
}
