package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.util.FileUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/file")
public class FileRestController {
    private final FileService fileService;

    public FileRestController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<FileEntity> saveFile(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "description", required = false) String description,
                                               @RequestParam(value = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                                               @RequestParam(value = "password", required = false) String password,
                                               @RequestParam(value = "hidden", defaultValue = "false") boolean hidden) {
        FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely, password, hidden);
        FileEntity fileEntity = fileService.saveFile(file, fileUploadRequest);
        if (fileEntity != null) {
            return ResponseEntity.ok(fileEntity);
        } else {
            return ResponseEntity.badRequest().build();
        }
    }


    @PostMapping("/share/{id}")
    public ResponseEntity<String> generateShareableLink(@PathVariable Long id, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(id);
        if (fileEntity == null) {
            return ResponseEntity.badRequest().body("File not found.");
        }

        String password = (String) request.getSession().getAttribute("password");
        if (fileEntity.passwordHash != null) {
            if (password == null || !fileService.checkPassword(fileEntity.uuid, password)) {
                System.out.println("Invalid or missing password.");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid or missing password in session.");
            }
        }

        String token = fileService.generateShareToken(id, LocalDate.now().plusDays(30));
        String shareLink = FileUtils.getShareLink(request, fileEntity, token);
        return ResponseEntity.ok(shareLink);
    }

    @GetMapping("/download/{uuid}/{token}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String uuid, @PathVariable String token) {
        try {
            StreamingResponseBody responseBody = fileService.streamFileAndInvalidateToken(uuid, token);
            if (responseBody == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            FileEntity fileEntity = fileService.getFile(uuid);

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileEntity.name + "\"")
                    .header("Content-Length", String.valueOf(fileEntity.size))
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
