package org.rostislav.quickdrop.service;

import java.io.File;
import java.io.FileInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileEncryptionUtils.decryptFile;
import static org.rostislav.quickdrop.util.FileEncryptionUtils.encryptFile;

@Service
public class FileService {
    @Value("${file.save.path}")
    private String fileSavePath;
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;

    public FileService(FileRepository fileRepository, PasswordEncoder passwordEncoder) {
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
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

    public FileEntity saveFile(MultipartFile file, FileUploadRequest fileUploadRequest) {
        if (!validateObjects(file, fileUploadRequest)) {
            return null;
        }

        logger.info("File received: {}", file.getOriginalFilename());

        String uuid = UUID.randomUUID().toString();
        Path path = Path.of(fileSavePath, uuid);

        if (fileUploadRequest.password == null || fileUploadRequest.password.isEmpty()) {
            if (!saveUnencryptedFile(file, path)) {
                return null;
            }
        } else {
            if (!saveEncryptedFile(path, file, fileUploadRequest)) {
                return null;
            }
        }

        FileEntity fileEntity = new FileEntity();
        fileEntity.name = file.getOriginalFilename();
        fileEntity.uuid = uuid;
        fileEntity.description = fileUploadRequest.description;
        fileEntity.size = file.getSize();
        fileEntity.keepIndefinitely = fileUploadRequest.keepIndefinitely;

        if (fileUploadRequest.password != null && !fileUploadRequest.password.isEmpty()) {
            fileEntity.passwordHash = passwordEncoder.encode(fileUploadRequest.password);
        }

        logger.info("FileEntity inserted into database: {}", fileEntity);
        return fileRepository.save(fileEntity);
    }

    private boolean saveUnencryptedFile(MultipartFile file, Path path) {
        try {
            Files.createFile(path);
            file.transferTo(path);
            logger.info("File saved: {}", path);
        } catch (
                Exception e) {
            logger.error("Error saving file: {}", e.getMessage());
            return false;
        }
        return true;
    }

    public boolean saveEncryptedFile(Path savePath, MultipartFile file, FileUploadRequest fileUploadRequest) {
        Path tempFile;
        try {
            tempFile = Files.createTempFile("Unencrypted", "tmp");
            file.transferTo(tempFile);
            logger.info("Unencrypted temp file saved: {}", tempFile);
        } catch (
                Exception e) {
            logger.error("Error saving unencrypted temp file: {}", e.getMessage());
            return false;
        }

        try {
            Path encryptedFile = Files.createFile(savePath);
            logger.info("Encrypting file: {}", encryptedFile);
            encryptFile(tempFile.toFile(), encryptedFile.toFile(), fileUploadRequest.password);
            logger.info("Encrypted file saved: {}", encryptedFile);
        } catch (
                Exception e) {
            logger.error("Error encrypting file: {}", e.getMessage());
            return false;
        }

        try {
            Files.delete(tempFile);
            logger.info("Temp file deleted: {}", tempFile);
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

    public ResponseEntity<StreamingResponseBody> downloadFile(Long id, String password) {
        FileEntity fileEntity = fileRepository.findById(id).orElse(null);
        if (fileEntity == null) {
            logger.info("File not found: {}", id);
            return ResponseEntity.notFound().build();
        }

        Path pathOfFile = Path.of(fileSavePath, fileEntity.uuid);
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
        fileRepository.save(fileEntity);
    }

    public boolean deleteFileFromFileSystem(String uuid) {
        Path path = Path.of(fileSavePath, uuid);
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
}
