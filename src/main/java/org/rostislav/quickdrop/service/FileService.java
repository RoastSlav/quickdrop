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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileService {
    private static final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileRepository fileRepository;
    @Value("${file.save.path}")
    private String fileSavePath;

    public FileService(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    public FileEntity saveFile(MultipartFile file, FileUploadRequest fileUploadRequest) {
        String uuid = UUID.randomUUID().toString();
        Path path = Path.of(fileSavePath, uuid);
        try {
            Files.createFile(path);
            Files.write(path, file.getBytes());
            logger.info("File saved: {}", path);
        } catch (Exception e) {
            return null;
        }

        FileEntity fileEntity = new FileEntity();
        fileEntity.name = file.getOriginalFilename();
        fileEntity.uuid = uuid;
        fileEntity.description = fileUploadRequest.description;
        fileEntity.size = file.getSize();
        fileEntity.keepIndefinitely = fileUploadRequest.keepIndefinitely;

        logger.info("FileEntity saved: {}", fileEntity);
        return fileRepository.save(fileEntity);
    }

    public List<FileEntity> getFiles() {
        return fileRepository.findAll();
    }

    public void deleteFile(Long id) {
        fileRepository.deleteById(id);
    }

    public ResponseEntity<Resource> downloadFile(Long id) {
        Optional<FileEntity> referenceById = fileRepository.findById(id);
        if (referenceById.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Path path = Path.of(fileSavePath, referenceById.get().uuid);
        try {
            Resource resource = new UrlResource(path.toUri());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + referenceById.get().name + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    public FileEntity getFile(Long id) {
        return fileRepository.findById(id).orElse(null);
    }

    public FileEntity getFile(String uuid) {
        return fileRepository.findByUUID(uuid).orElse(null);
    }
}
