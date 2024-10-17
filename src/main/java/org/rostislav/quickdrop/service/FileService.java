package org.rostislav.quickdrop.service;

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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.rostislav.quickdrop.util.DataValidator.validateObjects;
import static org.rostislav.quickdrop.util.FileEncryptionUtils.decryptFile;
import static org.rostislav.quickdrop.util.FileEncryptionUtils.encryptFile;

@Service
public class FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    private final PasswordEncoder passwordEncoder;
    @Value("${file.save.path}")
    private String fileSavePath;

    public FileService(FileRepository fileRepository, PasswordEncoder passwordEncoder) {
        this.fileRepository = fileRepository;
        this.passwordEncoder = passwordEncoder;
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

        logger.info("FileEntity saved: {}", fileEntity);
        return fileRepository.save(fileEntity);
    }

    private boolean saveUnencryptedFile(MultipartFile file, Path path) {
        try {
            Files.createFile(path);
            Files.write(path, file.getBytes());
            logger.info("File saved: {}", path);
        } catch (Exception e) {
            logger.error("Error saving file: {}", e.getMessage());
            return false;
        }
        return true;
    }

    public boolean saveEncryptedFile(Path savePath, MultipartFile file, FileUploadRequest fileUploadRequest) {
        Path tempFile;
        try {
            tempFile = Files.createTempFile("Unencrypted", "tmp");
            Files.write(tempFile, file.getBytes());
            logger.info("Unencrypted temp file saved: {}", tempFile);
        } catch (Exception e) {
            logger.error("Error saving unencrypted temp file: {}", e.getMessage());
            return false;
        }

        try {
            Path encryptedFile = Files.createFile(savePath);
            encryptFile(tempFile.toFile(), encryptedFile.toFile(), fileUploadRequest.password);
        } catch (Exception e) {
            logger.error("Error encrypting file: {}", e.getMessage());
            return false;
        }

        try {
            Files.delete(tempFile);
        } catch (Exception e) {
            logger.error("Error deleting temp file: {}", e.getMessage());
            return false;
        }

        return true;
    }

    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    public ResponseEntity<Resource> downloadFile(Long id, String password) {
        FileEntity fileEntity = fileRepository.findById(id).orElse(null);
        if (fileEntity == null) {
            return ResponseEntity.notFound().build();
        }

        Path pathOfFile = Path.of(fileSavePath, fileEntity.uuid);
        Path outputFile = null;
        if (fileEntity.passwordHash != null) {
            try {
                outputFile = File.createTempFile("Decrypted", "tmp").toPath();
                decryptFile(pathOfFile.toFile(), outputFile.toFile(), password);
            } catch (Exception e) {
                logger.error("Error decrypting file: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } else {
            outputFile = pathOfFile;
        }

        try {
            Resource resource = new UrlResource(outputFile.toUri());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileEntity.name + "\"")
                    .body(resource);
        } catch (Exception e) {
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
        } catch (Exception e) {
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
