package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/file")
public class FileRestController {
    private final FileService fileService;

    public FileRestController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<FileEntity> saveFile(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "description") String description,
                                               @RequestParam(value = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely) {
        FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely);
        FileEntity fileEntity = fileService.saveFile(file, fileUploadRequest);
        if (fileEntity != null) {
            return ResponseEntity.ok(fileEntity);
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping
    public ResponseEntity<List<FileEntity>> getFiles() {
        return ResponseEntity.ok(fileService.getFiles());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileEntity> getFile(@PathVariable Long id) {
        FileEntity file = fileService.getFile(id);
        if (file != null) {
            return ResponseEntity.ok(file);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        return fileService.downloadFile(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deleteFile(@PathVariable Long id) {
        fileService.deleteFile(id);
        return ResponseEntity.ok().build();
    }
}
