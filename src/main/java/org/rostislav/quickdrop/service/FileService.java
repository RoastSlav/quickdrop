package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.model.ShareTokenRequest;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.safeNumber;
import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileUtils.*;

@Service
public class FileService {
    public static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final FileHistoryLogRepository fileHistoryLogRepository;
    private final SessionService sessionService;
    private final FileEncryptionService fileEncryptionService;
    private final ShareTokenRepository shareTokenRepository;
    private final NotificationService notificationService;
    private static final int PUBLIC_ID_LENGTH = 8;

    @Lazy
    public FileService(FileRepository fileRepository, PasswordEncoder passwordEncoder, ApplicationSettingsService applicationSettingsService, FileHistoryLogRepository fileHistoryLogRepository, SessionService sessionService, FileEncryptionService fileEncryptionService, ShareTokenRepository shareTokenRepository, NotificationService notificationService) {
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.fileHistoryLogRepository = fileHistoryLogRepository;
        this.sessionService = sessionService;
        this.fileEncryptionService = fileEncryptionService;
        this.shareTokenRepository = shareTokenRepository;
        this.notificationService = notificationService;
    }

    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
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

    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    private FileEntity populateFileEntity(FileUploadRequest request, String uuid) {
        FileEntity fileEntity = new FileEntity();
        fileEntity.name = request.fileName;
        fileEntity.uuid = uuid;
        fileEntity.description = request.description;
        fileEntity.size = request.fileSize;
        fileEntity.keepIndefinitely = request.keepIndefinitely;
        fileEntity.hidden = request.hidden;
        boolean clientEncrypted = request.encryptionVersion != null && request.encryptionVersion > 0;
        fileEntity.encrypted = clientEncrypted || shouldEncrypt(request);
        fileEntity.encryptionVersion = clientEncrypted
            ? request.encryptionVersion
            : (fileEntity.encrypted ? 1 : 0);
        fileEntity.originalSize = request.plaintextSize != null ? request.plaintextSize : request.fileSize;
        fileEntity.folderUpload = request.folderUpload;
        fileEntity.folderName = request.folderName;
        fileEntity.folderManifest = request.folderManifest;

        if (request.password != null && !request.password.isBlank()) {
            fileEntity.passwordHash = passwordEncoder.encode(request.password);
        }

        return fileEntity;
    }

    public FileEntity getFile(String uuid) {
        return fileRepository.findByUUID(uuid).orElse(null);
    }

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

    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
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

    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DELETION);

        shareTokenRepository.deleteAllByFile(fileEntity);
        fileHistoryLogRepository.deleteByFileId(fileEntity.id);
        fileRepository.delete(fileEntity);
        return true;
    }

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
        if (fileEntity.encrypted && (fileEntity.encryptionVersion == null || fileEntity.encryptionVersion < 2)) {
            try {
                inputStream = fileEncryptionService.getDecryptedInputStream(filePath.toFile(), password);
            } catch (Exception e) {
                logger.error("Error decrypting file: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } else if (fileEntity.encrypted && fileEntity.encryptionVersion != null && fileEntity.encryptionVersion >= 2) {
            try {
                inputStream = new FileInputStream(filePath.toFile());
            } catch (FileNotFoundException e) {
                logger.error("File not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
        } else {
            try {
                inputStream = new FileInputStream(filePath.toFile());
            } catch (FileNotFoundException e) {
                logger.error("File not found: {}", filePath);
                return ResponseEntity.notFound().build();
            }
        }

        try {
            return createFileDownloadResponse(inputStream, fileEntity, request);
        } catch (Exception e) {
            logger.error("Error preparing file download response: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

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

        long previewLimit = applicationSettingsService.getMaxPreviewSizeBytes();
        long effectiveSize = fileEntity.originalSize != null ? fileEntity.originalSize : fileEntity.size;

        if (effectiveSize > previewLimit && !manualOverride) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        }

        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        String password = getFilePasswordFromSessionToken(request);

        InputStream inputStream;
        try {
            if (fileEntity.encrypted && (fileEntity.encryptionVersion == null || fileEntity.encryptionVersion < 2)) {
                inputStream = fileEncryptionService.getDecryptedInputStream(filePath.toFile(), password);
            } else {
                // For v2 (client-encrypted) and unencrypted, stream bytes as-is; client will decrypt when needed
                inputStream = new FileInputStream(filePath.toFile());
            }
        } catch (Exception e) {
            logger.error("Error preparing preview for file {}: {}", uuid, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String contentType = (fileEntity.encryptionVersion != null && fileEntity.encryptionVersion >= 2)
                ? "application/octet-stream"
                : guessContentType(fileEntity.name, isImage, isText, isPdf);

        StreamingResponseBody body = getStreamingResponseBody(inputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileEntity.name + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }

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

    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public void logDownload(String uuid, HttpServletRequest request) {
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) return;
        RequesterInfo requesterInfo = getRequesterInfo(request);
        fileHistoryLogRepository.save(new FileHistoryLog(fileEntity, FileHistoryType.DOWNLOAD, requesterInfo.ipAddress, requesterInfo.userAgent));
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DOWNLOAD);
    }

    private String getFilePasswordFromSessionToken(HttpServletRequest request) {
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        if (sessionToken == null) {
            return null;
        }

        return sessionService.getPasswordForFileSessionToken(sessionToken.toString()).getPassword();
    }

    @Cacheable(value = "publicFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntity> getVisibleFiles(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findAllNotHiddenFiles(pageable);
        }
        return fileRepository.searchNotHiddenFiles(query, pageable);
    }

    public long calculateTotalSpaceUsed() {
        return safeNumber(fileRepository.totalFileSizeForAllFiles());
    }

    public long getFileCount() {
        return fileRepository.count();
    }

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

    @Cacheable(value = "adminFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntityView> getFilesWithDownloadCounts(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findFilesWithDownloadCounts(pageable);
        }
        return fileRepository.searchFilesWithDownloadCounts(query, pageable);
    }

    public boolean checkFilePassword(String uuid, String password) {
        Optional<FileEntity> referenceByUUID = fileRepository.findByUUID(uuid);
        if (referenceByUUID.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceByUUID.get();
        return passwordEncoder.matches(password, fileEntity.passwordHash);
    }

    @CacheEvict(value = {"adminFiles", "analytics"}, allEntries = true)
    public StreamingResponseBody streamFileByShareToken(ShareTokenEntity shareTokenEntity, HttpServletRequest request) {
        if (!validateShareToken(shareTokenEntity)) {
            return null;
        }

        FileEntity fileEntity = shareTokenEntity.file;
        Path decryptedFilePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid + "-decrypted");
        Path filePathToStream;

        if (fileEntity.encryptionVersion != null && fileEntity.encryptionVersion >= 2) {
            filePathToStream = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        } else {
            filePathToStream = Files.exists(decryptedFilePath)
                    ? decryptedFilePath
                    : Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        }

        logHistory(fileEntity, request, FileHistoryType.DOWNLOAD);

        return outputStream -> {
            try {
                streamFile(filePathToStream, decryptedFilePath, fileEntity.uuid, outputStream);
            } finally {
                updateShareTokenAfterDownload(shareTokenEntity, fileEntity);
            }
        };
    }

    private void updateShareTokenAfterDownload(ShareTokenEntity shareTokenEntity, FileEntity fileEntity) {
        if (shareTokenEntity.numberOfAllowedDownloads != null) {
            shareTokenEntity.numberOfAllowedDownloads--;
        }

        if (!validateShareToken(shareTokenEntity)) {
            shareTokenRepository.delete(shareTokenEntity);
        } else {
            shareTokenRepository.save(shareTokenEntity);
        }
        logger.info("Share token updated/invalidated. File streamed successfully: {}", fileEntity.name);
    }

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

    private void logHistory(FileEntity fileEntity, HttpServletRequest request, FileHistoryType eventType) {
        RequesterInfo info = getRequesterInfo(request);
        fileHistoryLogRepository.save(new FileHistoryLog(fileEntity, eventType, info.ipAddress(), info.userAgent()));
        notificationService.notifyFileAction(fileEntity, eventType);
    }

    private String generateUniqueShareToken(FileEntity fileEntity) {
        String token;
        do {
            token = generateHashedToken(fileEntity);
        } while (shareTokenRepository.existsByShareToken(token));
        return token;
    }

    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, Integer numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }
        FileEntity file = optionalFile.get();

        String token = generateUniqueShareToken(file);
        ShareTokenEntity shareToken = new ShareTokenEntity(token, file, tokenExpirationDate, numberOfDownloads);
        shareTokenRepository.save(shareToken);

        return shareToken;
    }

    public ShareTokenEntity generateShareTokenForEncryptedFile(String uuid, LocalDate tokenExpirationDate, Integer numberOfDownloads, ShareTokenRequest shareTokenRequest) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        FileEntity file = optionalFile.get();

        String effectiveToken = shareTokenRequest.token;
        if (effectiveToken == null || effectiveToken.length() <= PUBLIC_ID_LENGTH) {
            throw new IllegalArgumentException("Encrypted share token missing secret portion");
        }

        ShareTokenEntity shareToken = new ShareTokenEntity(effectiveToken, file, tokenExpirationDate, numberOfDownloads);
        shareToken.publicId = shareTokenRequest.publicId;
        shareToken.wrappedDek = shareTokenRequest.wrappedDek;
        shareToken.wrapNonce = shareTokenRequest.wrapNonce;
        if (shareTokenRequest.secretHash != null) {
            shareToken.secretHash = shareTokenRequest.secretHash;
        } else {
            String secret = effectiveToken.substring(PUBLIC_ID_LENGTH);
            shareToken.secretHash = hashSecret(secret);
        }
        shareToken.encryptionVersion = shareTokenRequest.encryptionVersion != null ? shareTokenRequest.encryptionVersion : file.encryptionVersion;
        shareToken.tokenMode = shareTokenRequest.tokenMode != null ? shareTokenRequest.tokenMode : "encrypted-v2-share";

        logger.info("Generated encrypted v2 share token: publicId={}, mode={}, exp={}, downloads={}", shareToken.publicId, shareToken.tokenMode, tokenExpirationDate, numberOfDownloads);
        shareTokenRepository.save(shareToken);
        return shareToken;
    }

    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, Integer numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        FileEntity file = optionalFile.get();
        Path encryptedFilePath = Path.of(applicationSettingsService.getFileStoragePath(), file.uuid);
        Path decryptedFilePath = encryptedFilePath.resolveSibling(file.uuid + "-decrypted");

        if (file.encrypted && (file.encryptionVersion == null || file.encryptionVersion < 2) && !Files.exists(decryptedFilePath)) {
            try {
                String password = sessionService.getPasswordForFileSessionToken(sessionToken).getPassword();
                fileEncryptionService.decryptFile(encryptedFilePath.toFile(), decryptedFilePath.toFile(), password);
                logger.info("Decrypted file created alongside encrypted file: {}", decryptedFilePath);
            } catch (Exception e) {
                logger.error("Error decrypting file for sharing: {}", e.getMessage());
                throw new RuntimeException("Failed to decrypt file", e);
            }
        }

        ShareTokenEntity shareToken = generateShareToken(uuid, tokenExpirationDate, numberOfDownloads);
        logger.info("Share token generated for file: {}", file.name);
        return shareToken;
    }

    public Optional<ShareTokenEntity> getShareTokenEntityByToken(String token) {
        if (token == null || token.length() < PUBLIC_ID_LENGTH) {
            logger.warn("Share token lookup failed: token too short");
            return Optional.empty();
        }

        String publicId = token.substring(0, PUBLIC_ID_LENGTH);

        Optional<ShareTokenEntity> byPublicId = shareTokenRepository.findByPublicId(publicId);
        if (byPublicId.isPresent()) {
            logger.info("Share token lookup by publicId succeeded (v2 path)");
            return byPublicId;
        }

        Optional<ShareTokenEntity> legacy = shareTokenRepository.findByShareToken(token);
        if (legacy.isPresent()) {
            logger.info("Share token lookup by full token succeeded (legacy path)");
            return legacy;
        }

        logger.warn("Share token lookup failed: no entity for publicId={} or full token", publicId);
        return Optional.empty();
    }

    public boolean tokenSecretMatches(String token, ShareTokenEntity entity) {
        if (entity == null) return false;
        if (entity.encryptionVersion == null || entity.encryptionVersion < 2) {
            return true; // legacy tokens
        }

        if (token == null || token.length() <= PUBLIC_ID_LENGTH) {
            // Public-id-only tokens are allowed for v2; secret checked client-side via wrapped DEK
            return token != null && token.length() == PUBLIC_ID_LENGTH && token.equals(entity.publicId);
        }

        if (entity.publicId == null || entity.secretHash == null) {
            return false;
        }

        if (!token.startsWith(entity.publicId)) {
            logger.warn("Token secret mismatch: token does not start with publicId={}", entity.publicId);
            return false;
        }

        String secret = token.substring(PUBLIC_ID_LENGTH);
        String computed = hashSecret(secret);
        boolean matches = constantTimeEquals(computed, entity.secretHash);
        if (!matches) {
            logger.warn("Token secret hash mismatch for publicId={}", entity.publicId);
        }
        return matches;
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private ResponseEntity<StreamingResponseBody> createFileDownloadResponse(InputStream inputStream, FileEntity fileEntity, HttpServletRequest request) throws IOException {
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

    public boolean fileExistsInFileSystem(String uuid) {
        return Files.exists(Path.of(applicationSettingsService.getFileStoragePath(), uuid));
    }


    public boolean shouldEncrypt(FileUploadRequest request) {
        if (request.encryptionVersion != null && request.encryptionVersion >= 2) {
            return false;
        }
        return request.password != null && !request.password.isBlank() && applicationSettingsService.isEncryptionEnabled();
    }

    public record RequesterInfo(String ipAddress, String userAgent) {
    }
}
