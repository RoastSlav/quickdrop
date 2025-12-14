package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.rostislav.quickdrop.model.FileUploadRequest;
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
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.rostislav.quickdrop.util.DataValidator.nullToZero;
import static org.rostislav.quickdrop.util.DataValidator.validateObjects;

@Service
public class FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final FileHistoryLogRepository fileHistoryLogRepository;
    private final SessionService sessionService;
    private final FileEncryptionService fileEncryptionService;
    private final ShareTokenRepository shareTokenRepository;
    private final NotificationService notificationService;

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

    private static StreamingResponseBody getStreamingResponseBody(InputStream inputStream) {
        return outputStream -> {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        };
    }

    private static RequesterInfo getRequesterInfo(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        String ipAddress;

        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // The X-Forwarded-For header can contain multiple IPs, pick the first one
            ipAddress = forwardedFor.split(",")[0].trim();
        } else if (realIp != null && !realIp.isEmpty()) {
            ipAddress = realIp;
        } else {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return new RequesterInfo(ipAddress, userAgent);
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

        // Log initial upload event using client-provided context
        fileHistoryLogRepository.save(new FileHistoryLog(saved, FileHistoryType.UPLOAD, fileUploadRequest.uploaderIp, fileUploadRequest.uploaderUserAgent));
        notificationService.notifyFileAction(saved, FileHistoryType.UPLOAD, fileUploadRequest.uploaderIp, fileUploadRequest.uploaderUserAgent);

        return saved;
    }

    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    public boolean shouldEncrypt(FileUploadRequest request) {
        return request.password != null && !request.password.isBlank() && applicationSettingsService.isEncryptionEnabled();
    }

    public boolean isPreviewableImage(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".svg");
    }

    public boolean isPreviewableText(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".xml")
            || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".cxx") || lower.endsWith(".h") || lower.endsWith(".hpp")
            || lower.endsWith(".java") || lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")
            || lower.endsWith(".py") || lower.endsWith(".rb") || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".cs")
            || lower.endsWith(".php") || lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") || lower.endsWith(".css")
            || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".sql");
    }

    public boolean isPreviewablePdf(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        return fileEntity.name.toLowerCase().endsWith(".pdf");
    }

    public boolean isPreviewableJson(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".json") || lower.endsWith(".jsonl");
    }

    public boolean isPreviewableCsvOrTsv(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".csv") || lower.endsWith(".tsv");
    }

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
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DELETION, null, null);
        fileRepository.delete(fileEntity);
        fileHistoryLogRepository.deleteByFileId(fileEntity.id);
        return deleteFileFromFileSystem(fileEntity.uuid);
    }

    @Transactional
    @CacheEvict(value = {"publicFiles", "adminFiles", "analytics"}, allEntries = true)
    public boolean removeFileFromDatabase(String uuid) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DELETION, null, null);
        fileRepository.delete(fileEntity);
        fileHistoryLogRepository.deleteByFileId(fileEntity.id);
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

        StreamingResponseBody body = getStreamingResponseBody(inputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileEntity.name + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(body);
    }

    private String guessContentType(String fileName, boolean isImage, boolean isText, boolean isPdf) {
        if (isImage) {
            if (fileName.toLowerCase().endsWith(".svg")) return "image/svg+xml";
            if (fileName.toLowerCase().endsWith(".webp")) return "image/webp";
            if (fileName.toLowerCase().endsWith(".gif")) return "image/gif";
            if (fileName.toLowerCase().endsWith(".png")) return "image/png";
            return "image/jpeg";
        }
        if (isPdf) {
            return "application/pdf";
        }
        if (isText) {
            if (fileName.toLowerCase().endsWith(".json")) return "application/json";
            if (fileName.toLowerCase().endsWith(".xml")) return "application/xml";
            if (fileName.toLowerCase().endsWith(".csv")) return "text/csv";
            if (fileName.toLowerCase().endsWith(".tsv")) return "text/tab-separated-values";
            if (fileName.toLowerCase().endsWith(".md")) return "text/markdown";
            return "text/plain; charset=UTF-8";
        }
        return "application/octet-stream";
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
        notificationService.notifyFileAction(fileEntity, FileHistoryType.DOWNLOAD, requesterInfo.ipAddress, requesterInfo.userAgent);
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
        return nullToZero(fileRepository.totalFileSizeForAllFiles());
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

    public List<FileEntity> getNotHiddenFiles() {
        return fileRepository.findAllNotHiddenFiles();
    }

    @Cacheable(value = "adminFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntityView> getFilesWithDownloadCounts(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findFilesWithDownloadCounts(pageable);
        }
        return fileRepository.searchFilesWithDownloadCounts(query, pageable);
    }

    public Page<FileEntityView> getFilesWithDownloadCounts(Pageable pageable) {
        return getFilesWithDownloadCounts(pageable, null);
    }

    public List<FileEntityView> getAllFilesWithDownloadCounts() {
        return fileRepository.findAllFilesWithDownloadCounts();
    }


    public boolean validateShareToken(ShareTokenEntity token) {
        if (token == null) {
            return false;
        }

        boolean notExpired = token.tokenExpirationDate == null || !LocalDate.now().isAfter(token.tokenExpirationDate);
        boolean hasDownloads = token.numberOfAllowedDownloads == null || token.numberOfAllowedDownloads > 0;
        return notExpired && hasDownloads;
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
        Path filePathToStream = Files.exists(decryptedFilePath) ? decryptedFilePath : Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);

        logHistory(fileEntity, request, FileHistoryType.DOWNLOAD);

        return outputStream -> {
            try {
                streamFile(filePathToStream, decryptedFilePath, fileEntity.uuid, outputStream);
            } finally {
                updateShareTokenAfterDownload(shareTokenEntity, fileEntity);
            }
        };
    }

    private void streamFile(Path filePathToStream, Path decryptedFilePath, String uuid, OutputStream outputStream) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(filePathToStream.toFile())) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } catch (Exception e) {
            logger.error("Error streaming file for UUID: {}", uuid, e);
            throw e;
        } finally {
            // If there's a decrypted file, remove it after streaming
            if (filePathToStream.equals(decryptedFilePath)) {
                try {
                    Files.deleteIfExists(decryptedFilePath);
                    logger.info("Deleted decrypted file after download: {}", decryptedFilePath);
                } catch (IOException e) {
                    logger.error("Failed to delete decrypted file: {}", decryptedFilePath, e);
                }
            }
        }
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
        notificationService.notifyFileAction(fileEntity, eventType, info.ipAddress(), info.userAgent());
    }

    private String generateUniqueShareToken(FileEntity fileEntity) {
        String token;
        do {
            token = generateHashedToken(fileEntity);
        } while (shareTokenRepository.existsByShareToken(token));
        return token;
    }

    private String generateHashedToken(FileEntity fileEntity) {
        String seed = String.join(":",
                fileEntity.uuid,
                String.valueOf(fileEntity.size),
                String.valueOf(fileEntity.uploadDate),
                String.valueOf(System.nanoTime()),
                String.valueOf(ThreadLocalRandom.current().nextLong())
        );

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            String base62 = toBase62(digest);
            return base62.length() >= 5 ? base62.substring(0, 5) : String.format("%1$-5s", base62).replace(' ', '0');
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String toBase62(byte[] bytes) {
        final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        BigInteger value = new BigInteger(1, bytes);
        if (value.equals(BigInteger.ZERO)) {
            return alphabet.substring(0, 1);
        }

        StringBuilder builder = new StringBuilder();
        BigInteger base = BigInteger.valueOf(alphabet.length());

        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = value.divideAndRemainder(base);
            builder.append(alphabet.charAt(divRem[1].intValue()));
            value = divRem[0];
        }

        return builder.reverse().toString();
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

    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, Integer numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        FileEntity file = optionalFile.get();
        Path encryptedFilePath = Path.of(applicationSettingsService.getFileStoragePath(), file.uuid);
        Path decryptedFilePath = encryptedFilePath.resolveSibling(file.uuid + "-decrypted");

        if (file.encrypted && !Files.exists(decryptedFilePath)) {
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
        return shareTokenRepository.findByShareToken(token);
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

    private record RequesterInfo(String ipAddress, String userAgent) {
    }
}
