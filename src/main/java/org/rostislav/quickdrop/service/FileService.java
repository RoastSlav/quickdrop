package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.DownloadLog;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.repository.DownloadLogRepository;
import org.rostislav.quickdrop.repository.FileRepository;
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

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.rostislav.quickdrop.util.DataValidator.nullToZero;
import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileEncryptionUtils.decryptFile;
import static org.rostislav.quickdrop.util.FileEncryptionUtils.encryptFile;

@Service
public class FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final DownloadLogRepository downloadLogRepository;
    private final File tempDir = Paths.get(System.getProperty("java.io.tmpdir")).toFile();

    @Lazy
    public FileService(FileRepository fileRepository, PasswordEncoder passwordEncoder, ApplicationSettingsService applicationSettingsService, DownloadLogRepository downloadLogRepository) {
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.downloadLogRepository = downloadLogRepository;
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

    public void saveChunk(MultipartFile file, String fileName, int chunkNumber) throws IOException {
        File chunkFile = new File(tempDir, getFileChunkName(fileName) + chunkNumber);
        try (FileOutputStream fos = new FileOutputStream(chunkFile)) {
            fos.write(file.getBytes());
        }
    }

    public FileEntity assembleChunks(String fileName, int totalChunks, FileUploadRequest fileUploadRequest) throws IOException {
        File finalFile = new File(tempDir, fileName);
        boolean successfullyCreated = finalFile.createNewFile();
        if (!successfullyCreated) {
            throw new IOException("Failed to create new file");
        }

        try (FileOutputStream fos = new FileOutputStream(finalFile)) {
            for (int i = 0; i < totalChunks; i++) {
                File partFile = new File(tempDir, getFileChunkName(fileName) + i);
                Files.copy(partFile.toPath(), fos);
                Files.delete(partFile.toPath());
            }
        }

        return saveFile(finalFile, fileUploadRequest);
    }

    public void deleteTempFiles(String fileName) {
        File[] tempFiles = tempDir.listFiles((dir, name) -> name.startsWith(getFileChunkName(fileName)));

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

    private String getFileChunkName(String fileName) {
        return fileName + "_chunk";
    }

    public FileEntity saveFile(File file, FileUploadRequest fileUploadRequest) {
        if (!validateObjects(file, fileUploadRequest)) {
            return null;
        }

        logger.info("File received: {}", file.getName());

        String uuid = UUID.randomUUID().toString();
        Path path = Path.of(applicationSettingsService.getFileStoragePath(), uuid);

        if (fileUploadRequest.password == null || fileUploadRequest.password.isEmpty()) {
            if (!moveAndRenameUnencryptedFile(file, path)) {
                return null;
            }
        } else {
            if (!saveEncryptedFile(path, file, fileUploadRequest)) {
                return null;
            }
        }

        FileEntity fileEntity = new FileEntity();
        fileEntity.name = file.getName();
        fileEntity.uuid = uuid;
        fileEntity.description = fileUploadRequest.description;
        fileEntity.size = file.getTotalSpace();
        fileEntity.keepIndefinitely = fileUploadRequest.keepIndefinitely;
        fileEntity.hidden = fileUploadRequest.hidden;

        if (fileUploadRequest.password != null && !fileUploadRequest.password.isEmpty()) {
            fileEntity.passwordHash = passwordEncoder.encode(fileUploadRequest.password);
        }

        logger.info("FileEntity inserted into database: {}", fileEntity);
        return fileRepository.save(fileEntity);
    }

    private boolean moveAndRenameUnencryptedFile(File file, Path path) {
        try {
            Files.move(file.toPath(), path);
            logger.info("File saved: {}", path);
        } catch (
                Exception e) {
            logger.error("Error saving file: {}", e.getMessage());
            return false;
        }
        return true;
    }

    public boolean saveEncryptedFile(Path savePath, File file, FileUploadRequest fileUploadRequest) {
        try {
            Path encryptedFile = Files.createFile(savePath);
            logger.info("Encrypting file: {}", encryptedFile);
            encryptFile(file, encryptedFile.toFile(), fileUploadRequest.password);
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


    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    public ResponseEntity<StreamingResponseBody> downloadFile(Long id, String password, HttpServletRequest request) {
        FileEntity fileEntity = fileRepository.findById(id).orElse(null);
        if (fileEntity == null) {
            logger.info("File not found: {}", id);
            return ResponseEntity.notFound().build();
        }

        Path pathOfFile = Path.of(applicationSettingsService.getFileStoragePath(), fileEntity.uuid);
        Path outputFile = null;
        if (fileEntity.passwordHash != null) {
            try {
                outputFile = File.createTempFile("Decrypted", "tmp").toPath();
                logger.info("Decrypting file: {}", pathOfFile);
                decryptFile(pathOfFile.toFile(), outputFile.toFile(), password);
                logger.info("File decrypted: {}", outputFile);
            } catch (
                    Exception e) {
                logger.error("Error decrypting file: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } else {
            outputFile = pathOfFile;
        }

        StreamingResponseBody responseBody = getStreamingResponseBody(outputFile, fileEntity);

        try {
            Resource resource = new UrlResource(outputFile.toUri());
            logger.info("Sending file: {}", fileEntity);
            logDownload(fileEntity, request);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + URLEncoder.encode(fileEntity.name, StandardCharsets.UTF_8) + "\"")
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(resource.contentLength()))
                    .body(responseBody);
        } catch (
                Exception e) {
            logger.error("Error reading file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void logDownload(FileEntity fileEntity, HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        String downloaderIp;

        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // The X-Forwarded-For header can contain multiple IPs, pick the first one
            downloaderIp = forwardedFor.split(",")[0].trim();
        } else if (realIp != null && !realIp.isEmpty()) {
            downloaderIp = realIp;
        } else {
            downloaderIp = request.getRemoteAddr();
        }

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        DownloadLog downloadLog = new DownloadLog(fileEntity, downloaderIp, userAgent);
        downloadLogRepository.save(downloadLog);
    }


    public FileEntity getFile(Long id) {
        return fileRepository.findById(id).orElse(null);
    }

    public FileEntity getFile(String uuid) {
        return fileRepository.findByUUID(uuid).orElse(null);
    }

    public void extendFile(Long id) {
        Optional<FileEntity> referenceById = fileRepository.findById(id);
        if (referenceById.isEmpty()) {
            return;
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.uploadDate = LocalDate.now();
        logger.info("File extended: {}", fileEntity);
        fileRepository.save(fileEntity);
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

    public boolean deleteFile(Long id) {
        Optional<FileEntity> referenceById = fileRepository.findById(id);
        if (referenceById.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceById.get();
        fileRepository.delete(fileEntity);
        return deleteFileFromFileSystem(fileEntity.uuid);
    }

    public boolean checkPassword(String uuid, String password) {
        Optional<FileEntity> referenceByUUID = fileRepository.findByUUID(uuid);
        if (referenceByUUID.isEmpty()) {
            return false;
        }

        FileEntity fileEntity = referenceByUUID.get();
        return passwordEncoder.matches(password, fileEntity.passwordHash);
    }

    public List<FileEntity> searchFiles(String query) {
        return fileRepository.searchFiles(query);
    }

    public List<FileEntity> searchNotHiddenFiles(String query) {
        return fileRepository.searchNotHiddenFiles(query);
    }

    public long calculateTotalSpaceUsed() {
        return nullToZero(fileRepository.totalFileSizeForAllFiles());
    }

    public void updateKeepIndefinitely(Long id, boolean keepIndefinitely) {
        Optional<FileEntity> referenceById = fileRepository.findById(id);
        if (referenceById.isEmpty()) {
            return;
        }

        if (!keepIndefinitely) {
            extendFile(id);
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.keepIndefinitely = keepIndefinitely;
        logger.info("File keepIndefinitely updated: {}", fileEntity);
        fileRepository.save(fileEntity);
    }

    public void toggleHidden(Long id) {
        Optional<FileEntity> referenceById = fileRepository.findById(id);
        if (referenceById.isEmpty()) {
            return;
        }

        FileEntity fileEntity = referenceById.get();
        fileEntity.hidden = !fileEntity.hidden;
        logger.info("File hidden updated: {}", fileEntity);
        fileRepository.save(fileEntity);
    }

    public List<FileEntity> getNotHiddenFiles() {
        return fileRepository.findAllNotHiddenFiles();
    }

    public List<FileEntityView> getAllFilesWithDownloadCounts() {
        return fileRepository.findAllFilesWithDownloadCounts();
    }

    public String generateShareToken(Long fileId, LocalDate tokenExpirationDate) {
        Optional<FileEntity> optionalFile = fileRepository.findById(fileId);
        if (optionalFile.isEmpty()) {
            throw new IllegalArgumentException("File not found");
        }

        FileEntity file = optionalFile.get();
        String token = UUID.randomUUID().toString(); // Generate a unique token
        file.shareToken = token;
        file.tokenExpirationDate = tokenExpirationDate;
        fileRepository.save(file);

        logger.info("Share token generated for file: {}", file.name);
        return token;
    }

    public boolean validateShareToken(String uuid, String token) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);
        if (optionalFile.isEmpty()) {
            return false;
        }

        FileEntity file = optionalFile.get();
        if (!token.equals(file.shareToken)) {
            return false;
        }

        return file.tokenExpirationDate == null || !LocalDate.now().isAfter(file.tokenExpirationDate);
    }

    private void writeFileToStream(String uuid, OutputStream outputStream) {
        Path path = Path.of(applicationSettingsService.getFileStoragePath(), uuid);
        try (FileInputStream inputStream = new FileInputStream(path.toFile())) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } catch (
                Exception e) {
            logger.error("Error writing file to stream: {}", e.getMessage());
        }
    }

    public StreamingResponseBody streamFileAndInvalidateToken(String uuid, String token, HttpServletRequest request) {
        Optional<FileEntity> optionalFile = fileRepository.findByUUID(uuid);

        if (optionalFile.isEmpty() || !validateShareToken(uuid, token)) {
            return null;
        }

        FileEntity fileEntity = optionalFile.get();
        logDownload(fileEntity, request);

        return outputStream -> {
            try {
                writeFileToStream(uuid, outputStream);

                fileEntity.shareToken = null;
                fileEntity.tokenExpirationDate = null;
                fileRepository.save(fileEntity);

                logger.info("Share token invalidated and file streamed successfully: {}", fileEntity.name);
            } catch (Exception e) {
                logger.error("Error streaming file or invalidating token for UUID: {}", uuid, e);
            }
        };
    }
}
