package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.util.FileUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/file")
public class FileRestController {
    private final FileService fileService;

    public FileRestController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<?> handleChunkUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileName") String fileName,
            @RequestParam("chunkNumber") int chunkNumber,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "keepIndefinitely", defaultValue = "false") Boolean keepIndefinitely,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "hidden", defaultValue = "false") Boolean hidden) {
        try {
            fileService.saveFileChunk(file, fileName, chunkNumber);

            if (chunkNumber + 1 == totalChunks) {
                return fileService.finalizeFile(fileName, totalChunks, description, keepIndefinitely, password, hidden);
            }

        } catch (IOException e) {
            fileService.deleteChunkFilesFromTemp(fileName);
            fileService.deleteFullFileFromTemp(fileName);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Error processing chunk\"}");
        }

        Map<String, String> response = new HashMap<>();
        response.put("message", "Chunk " + chunkNumber + " uploaded successfully");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/share/{id}")
    public ResponseEntity<String> generateShareableLink(@PathVariable Long id, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(id);
        if (fileEntity == null) {
            return ResponseEntity.badRequest().body("File not found.");
        }

        if (!isFilePasswordValid(fileEntity, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid or missing password in session.");
        }

        String token = fileService.generateShareToken(id, LocalDate.now().plusDays(30));
        String shareLink = FileUtils.getShareLink(request, fileEntity, token);
        return ResponseEntity.ok(shareLink);
    }

    @GetMapping("/download/{uuid}/{token}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String uuid, @PathVariable String token, HttpServletRequest request) {
        try {
            StreamingResponseBody responseBody = fileService.streamFileAndInvalidateToken(uuid, token, request);
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

    private boolean isFilePasswordValid(FileEntity fileEntity, HttpServletRequest request) {
        String sessionPassword = (String) request.getSession().getAttribute("password");
        if (fileEntity.passwordHash != null) {
            return sessionPassword != null && fileService.checkFilePassword(fileEntity.uuid, sessionPassword);
        }
        return true;
    }
}
