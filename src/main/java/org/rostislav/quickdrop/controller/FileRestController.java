package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
                                               @RequestParam(value = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                                               @RequestParam(value = "password", required = false) String password) {
        FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely, password);
        FileEntity fileEntity = fileService.saveFile(file, fileUploadRequest);
        if (fileEntity != null) {
            return ResponseEntity.ok(fileEntity);
        } else {
            return ResponseEntity.badRequest().build();
        }
    }
}
