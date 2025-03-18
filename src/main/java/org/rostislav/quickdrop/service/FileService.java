package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.DownloadLog;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.FileRenewalLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.repository.DownloadLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.RenewalLogRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.rostislav.quickdrop.util.DataValidator.nullToZero;
import static org.rostislav.quickdrop.util.DataValidator.validateObjects;

@Service
public class FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final DownloadLogRepository downloadLogRepository;
    private final File tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toFile();
    private final SessionService sessionService;
    private final RenewalLogRepository renewalLogRepository;
    private final FileEncryptionService fileEncryptionService;
    private final ShareTokenRepository shareTokenRepository;

    @Lazy
    public FileService(FileRepository fileRepository, PasswordEncoder passwordEncoder, ApplicationSettingsService applicationSettingsService, DownloadLogRepository downloadLogRepository, SessionService sessionService, RenewalLogRepository renewalLogRepository, FileEncryptionService fileEncryptionService, ShareTokenRepository shareTokenRepository) {
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.downloadLogRepository = downloadLogRepository;
        this.sessionService = sessionService;
        this.renewalLogRepository = renewalLogRepository;
        this.fileEncryptionService = fileEncryptionService;
        this.shareTokenRepository = shareTokenRepository;
    }

    private static StreamingResponseBody getStreamingResponseBody(Path outputFile, FileEntity fileEntity) {
        return outputStream -> {
            try (FileInputStream inputStream = new FileInputStream(outputFile.toFile())) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            } finally {
                if (fileEntity.passwordHash != null) {
                    try {
                        Files.delete(outputFile);
                        logger.info("Decrypted file deleted: {}", outputFile);
                    } catch (
                            Exception e) {
                        logger.error("Error deleting decrypted file: {}", e.getMessage());
                    }
                }
            }
        };
    }

    public void saveFileChunk(MultipartFile file, String fileName, int chunkNumber) throws IOException {
        File chunkFile = new File(tempDir, getFileChunkName(fileName, chunkNumber));
        try (FileOutputStream fos = new FileOutputStream(chunkFile)) {
            fos.write(file.getBytes());
        }
    }

    public FileEntity assembleChunks(String fileName, int totalChunks, FileUploadRequest fileUploadRequest) throws IOException {
        File finalFile = new File(tempDir, fileName);

        if (!finalFile.createNewFile()) {
            throw new IOException("Failed to create new file");
        }

        mergeChunksIntoFile(finalFile, fileName, totalChunks);
        deleteChunkFilesFromTemp(fileName);

        return saveFile(finalFile, fileUploadRequest);
    }

    private void mergeChunksIntoFile(File finalFile, String fileName, int totalChunks) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(finalFile)) {
            for (int i = 0; i < totalChunks; i++) {
                File chunk = new File(tempDir, fileName + "_chunk_" + i);
                try {
                    Files.copy(chunk.toPath(), fos);
                    Files.delete(chunk.toPath());
                } catch (IOException ex) {
                    logger.error("Error processing chunk {}: {}", i, ex.getMessage());
                    throw ex;
                }
            }
            logger.info("All chunks merged into file: {}", finalFile);
        }
    }

    public void deleteChunkFilesFromTemp(String fileName) {
        File[] tempFiles = tempDir.listFiles((dir, name) -> name.startsWith(fileName + "_chunk_"));

        if (tempFiles != null) {
            for (File tempFile : tempFiles) {
                if (tempFile.delete()) {
                    logger.info("Deleted temp file: {}", tempFile);
                } else {
                    logger.error("Failed to delete temp file: {}", tempFile);
                }
            }
        }
    }

    public void deleteFullFileFromTemp(String fileName) {
        Path assembledFilePath = Paths.get(tempDir.getAbsolutePath(), fileName);
        try {
            if (Files.exists(assembledFilePath)) {
                Files.delete(assembledFilePath);
                logger.info("Deleted assembled file: {}", assembledFilePath);
            }
        } catch (IOException e) {
            logger.error("Failed to delete assembled file: {}", assembledFilePath, e);
        }
    }

    public FileEntity saveFile(File file, FileUploadRequest fileUploadRequest) {
        if (!validateObjects(file, fileUploadRequest)) {
            return null;
        }

        logger.info("Saving file: {}", file.getName());

        String uuid = UUID.randomUUID().toString();
        while (fileRepository.findByUUID(uuid).isPresent()) {
            uuid = UUID.randomUUID().toString();
        }

        Path targetPath = Path.of(applicationSettingsService.getFileStoragePath(), uuid);

        FileEntity fileEntity = populateFileEntity(file, fileUploadRequest, uuid);

        boolean isEncrypted = fileUploadRequest.password != null && !fileUploadRequest.password.isBlank();
        if (isEncrypted) {
            if (!saveEncryptedFile(targetPath, file, fileUploadRequest)) return null;
        } else {
            if (!moveAndRenameUnencryptedFile(file, targetPath)) return null;
        }

        logger.info("FileEntity inserted into database: {}", fileEntity);
        return fileRepository.save(fileEntity);
    }

    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    private FileEntity populateFileEntity(File file, FileUploadRequest request, String uuid) {
        FileEntity fileEntity = new FileEntity();
        fileEntity.name = file.getName();
        fileEntity.uuid = uuid;
        fileEntity.description = request.description;
        fileEntity.size = file.length();
        fileEntity.keepIndefinitely = request.keepIndefinitely;
        fileEntity.hidden = request.hidden;

        if (request.password != null && !request.password.isBlank()) {
            fileEntity.passwordHash = passwordEncoder.encode(request.password);
        }

        return fileEntity;
    }

    public FileEntity getFile(Long id) {
        return fileRepository.findById(id).orElse(null);
    }

    public FileEntity getFile(String uuid) {
        return fileRepository.findByUUID(uuid).orElse(null);
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
    public boolean deleteFileFromDatabaseAndFileSystem(String uuid) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        fileRepository.delete(fileEntity);
        downloadLogRepository.deleteByFileId(fileEntity.id);
        return deleteFileFromFileSystem(fileEntity.uuid);
    }

    @Transactional
    public boolean removeFileFromDatabase(String uuid) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        fileRepository.delete(fileEntity);
        downloadLogRepository.deleteByFileId(fileEntity.id);
        return true;
    }

    public ResponseEntity<StreamingResponseBody> downloadFile(String uuid, HttpServletRequest request) {
        FileEntity fileEntity = fileRepository.findByUUID(uuid).orElse(null);
        if (fileEntity == null) {
            logger.info("File not found: {}", uuid);
            return ResponseEntity.notFound().build();
        }

        Path filePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        String password = getFilePasswordFromSessionToken(request);
        Path decryptedFilePath = decryptFileIfNeeded(fileEntity, filePath, password);
        if (decryptedFilePath == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        try {
            return createFileDownloadResponse(decryptedFilePath, fileEntity, request);
        } catch (Exception e) {
            logger.error("Error preparing file download response: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String getFilePasswordFromSessionToken(HttpServletRequest request) {
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        if (sessionToken == null) {
            return null;
        }

        return sessionService.getPasswordForFileSessionToken(sessionToken.toString()).getPassword();
    }

    public List<FileEntity> searchNotHiddenFiles(String query) {
        return fileRepository.searchNotHiddenFiles(query);
    }

    public long calculateTotalSpaceUsed() {
        return nullToZero(fileRepository.totalFileSizeForAllFiles());
    }

    public void extendFile(String uuid, HttpServletRequest request) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            return;
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.uploadDate = LocalDate.now();
        logger.info("File extended: {}", fileEntity);
        fileRepository.save(fileEntity);
        logFileRenewal(fileEntity, request);
    }

    public FileEntity toggleHidden(String uuid) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            logger.info("File not found for 'toggle hidden': {}", uuid);
            return null;
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.hidden = !fileEntity.hidden;
        logger.info("File hidden updated: {}", fileEntity);
        fileRepository.save(fileEntity);
        return fileEntity;
    }

    public List<FileEntity> getNotHiddenFiles() {
        return fileRepository.findAllNotHiddenFiles();
    }

    public List<FileEntityView> getAllFilesWithDownloadCounts() {
        return fileRepository.findAllFilesWithDownloadCounts();
    }


    public boolean validateShareToken(ShareTokenEntity token) {
        return (token.tokenExpirationDate == null || !LocalDate.now().isAfter(token.tokenExpirationDate)) && token.numberOfAllowedDownloads > 0;
    }

    public boolean checkFilePassword(String uuid, String password) {
        Optional<FileEntity> referenceByUUID = fileRepository.findByUUID(uuid);
        if (referenceByUUID.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceByUUID.get();
        return passwordEncoder.matches(password, fileEntity.passwordHash);
    }

    public StreamingResponseBody streamFileAndInvalidateToken(String uuid, String token, HttpServletRequest request) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        ShareTokenEntity shareTokenEntity = shareTokenRepository.getShareTokenEntityByToken(token);

        if (optionalFile.isEmpty() || !validateShareToken(shareTokenEntity)) {
            return null;
        }

        FileEntity fileEntity = optionalFile.get();
        Path decryptedFilePath = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid + "-decrypted");
        Path filePathToStream;

        // Decide whether to stream the decrypted file or the encrypted file
        if (Files.exists(decryptedFilePath)) {
            filePathToStream = decryptedFilePath;
        } else {
            filePathToStream = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        }

        logDownload(fileEntity, request);

        return outputStream -> {
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
                // Delete the decrypted file after streaming if it exists
                if (filePathToStream.equals(decryptedFilePath)) {
                    try {
                        Files.deleteIfExists(decryptedFilePath);
                        logger.info("Deleted decrypted file after download: {}", decryptedFilePath);
                    } catch (IOException e) {
                        logger.error("Failed to delete decrypted file: {}", decryptedFilePath, e);
                    }
                }
            }

            // Update and/or delete the share token
            shareTokenEntity.numberOfAllowedDownloads--;
            if (!validateShareToken(shareTokenEntity)) {
                shareTokenRepository.delete(shareTokenEntity);
            } else {
                shareTokenRepository.save(shareTokenEntity);
            }

            logger.info("Share token invalidated and file streamed successfully: {}", fileEntity.name);
        };
    }

    public ResponseEntity<?> finalizeFile(String fileName, int totalChunks, String description,
                                          Boolean keepIndefinitely, String password, Boolean hidden) throws IOException {
        FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely, password, hidden);
        FileEntity fileEntity = assembleChunks(fileName, totalChunks, fileUploadRequest);

        if (fileEntity != null) {
            return ResponseEntity.ok(fileEntity);
        }

        return ResponseEntity.badRequest().build();
    }

    private boolean saveEncryptedFile(Path savePath, File file, FileUploadRequest fileUploadRequest) {
        try {
            Path encryptedFile = Files.createFile(savePath);
            logger.info("Encrypting file: {}", encryptedFile);
            fileEncryptionService.encryptFile(file, encryptedFile.toFile(), fileUploadRequest.password);
            logger.info("Encrypted file saved: {}", encryptedFile);
        } catch (
                Exception e) {
            logger.error("Error encrypting file: {}", e.getMessage());
            return false;
        }

        try {
            Files.delete(file.toPath());
            logger.info("Temp file deleted: {}", file);
        } catch (
                Exception e) {
            logger.error("Error deleting temp file: {}", e.getMessage());
            return false;
        }

        return true;
    }

    private boolean moveAndRenameUnencryptedFile(File file, Path path) {
        for (int retry = 0; retry < 3; retry++) {
            try {
                Files.move(file.toPath(), path, StandardCopyOption.REPLACE_EXISTING);
                logger.info("File moved successfully: {}", path);
                return true;
            } catch (IOException e) {
                logger.error("Attempt {}/3 failed to move file {}: {}", retry + 1, file.getName(), e.getMessage());
            }
        }
        logger.error("Failed to move file after 3 attempts: {}", file.getName());
        return false;
    }

    public FileEntity updateKeepIndefinitely(String uuid, boolean keepIndefinitely, HttpServletRequest request) {
        Optional<FileEntity> referenceById = fileRepository.findByUUID(uuid);
        if (referenceById.isEmpty()) {
            logger.info("File not found for 'update keep indefinitely': {}", uuid);
            return null;
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

    private void logDownload(FileEntity fileEntity, HttpServletRequest request) {
        RequesterInfo info = getRequesterInfo(request);
        DownloadLog downloadLog = new DownloadLog(fileEntity, info.ipAddress(), info.userAgent());
        downloadLogRepository.save(downloadLog);
    }

    private void logFileRenewal(FileEntity fileEntity, HttpServletRequest request) {
        RequesterInfo info = getRequesterInfo(request);
        FileRenewalLog fileRenewalLog = new FileRenewalLog(fileEntity, info.ipAddress(), info.userAgent());
        renewalLogRepository.save(fileRenewalLog);
    }

    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, int numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }
        FileEntity file = optionalFile.get();

        String token = UUID.randomUUID().toString();
        ShareTokenEntity shareToken = new ShareTokenEntity(token, file, tokenExpirationDate, numberOfDownloads);
        shareTokenRepository.save(shareToken);

        return shareToken;
    }

    public ShareTokenEntity generateShareToken(String uuid, LocalDate tokenExpirationDate, String sessionToken, int numberOfDownloads) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        FileEntity file = optionalFile.get();
        Path encryptedFilePath = Path.of(applicationSettingsService.getFileStoragePath(), file.uuid);
        Path decryptedFilePath = encryptedFilePath.resolveSibling(file.uuid + "-decrypted");

        // Decrypt the file if necessary
        if (file.passwordHash != null && !Files.exists(decryptedFilePath)) {
            try {
                String password = sessionService.getPasswordForFileSessionToken(sessionToken).getPassword();
                fileEncryptionService.decryptFile(encryptedFilePath.toFile(), decryptedFilePath.toFile(), password);
                logger.info("Decrypted file created alongside encrypted file: {}", decryptedFilePath);
            } catch (Exception e) {
                logger.error("Error decrypting file for sharing: {}", e.getMessage());
                throw new RuntimeException("Failed to decrypt file", e);
            }
        }

        // Generate the share token
        ShareTokenEntity shareToken = generateShareToken(uuid, tokenExpirationDate, numberOfDownloads);
        shareTokenRepository.save(shareToken);

        logger.info("Share token generated for file: {}", file.name);
        return shareToken;
    }

    public ShareTokenEntity getShareTokenEntityByToken(String token) {
        return shareTokenRepository.getShareTokenEntityByToken(token);
    }

    private record RequesterInfo(String ipAddress, String userAgent) {
    }

    private String getFileChunkName(String fileName, int chunkNumber) {
        return fileName + "_chunk_" + chunkNumber;
    }

    private Path decryptFileIfNeeded(FileEntity fileEntity, Path filePath, String password) {
        if (fileEntity.passwordHash == null) {
            return filePath;
        }
        try {
            Path tempFile = File.createTempFile("Decrypted", "tmp").toPath();
            logger.info("Decrypting file: {}", filePath);
            fileEncryptionService.decryptFile(filePath.toFile(), tempFile.toFile(), password);
            logger.info("File decrypted: {}", tempFile);
            return tempFile;
        } catch (Exception e) {
            logger.error("Error decrypting file: {}", e.getMessage());
            return null;
        }
    }

    private ResponseEntity<StreamingResponseBody> createFileDownloadResponse(Path filePath, FileEntity fileEntity, HttpServletRequest request) throws IOException {
        StreamingResponseBody responseBody = getStreamingResponseBody(filePath, fileEntity);
        Resource resource = new UrlResource(filePath.toUri());
        logger.info("Sending file: {}", fileEntity);
        logDownload(fileEntity, request);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + URLEncoder.encode(fileEntity.name, StandardCharsets.UTF_8) + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(resource.contentLength()))
                .body(responseBody);
    }

    public boolean fileExistsInFileSystem(String uuid) {
        return Files.exists(Path.of(applicationSettingsService.getFileStoragePath(), uuid));
    }
}
