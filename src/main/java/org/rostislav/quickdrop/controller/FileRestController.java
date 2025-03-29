package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.AsyncFileMergeService;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.LocalDate;

import static org.springframework.http.ResponseEntity.ok;

@RestController
@RequestMapping("/api/file")
public class FileRestController {
    private static final Logger logger = LoggerFactory.getLogger(FileRestController.class);
    private final FileService fileService;
    private final SessionService sessionService;
    private final AsyncFileMergeService asyncFileMergeService;

    public FileRestController(FileService fileService, SessionService sessionService, AsyncFileMergeService asyncFileMergeService) {
        this.fileService = fileService;
        this.sessionService = sessionService;
        this.asyncFileMergeService = asyncFileMergeService;
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<?> handleChunkUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("fileName") String fileName,
            @RequestParam("chunkNumber") int chunkNumber,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam(value = "fileSize", required = false) Long fileSize,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "keepIndefinitely", defaultValue = "false") Boolean keepIndefinitely,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "hidden", defaultValue = "false") Boolean hidden) {

        if (chunkNumber == 0) {
            logger.info("Upload started for file: {}", fileName);
        }

        try {
            logger.info("Submitting chunk {} of {} for file: {}", chunkNumber, totalChunks, fileName);

            FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely, password, hidden, fileName, totalChunks, fileSize);
            FileEntity fileEntity = asyncFileMergeService.submitChunk(fileUploadRequest, file, chunkNumber);
            return ResponseEntity.ok(fileEntity);
        } catch (IOException e) {
            logger.error("Error processing chunk {} for file {}: {}", chunkNumber, fileName, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Error processing chunk\"}");
        }
    }

    @PostMapping("/share/{uuid}")
    public ResponseEntity<String> generateShareableLink(@PathVariable String uuid, @RequestParam("expirationDate") LocalDate expirationDate, @RequestParam("nOfDownloads") int numberOfDownloads, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(uuid);
        if (fileEntity == null) {
            return ResponseEntity.badRequest().body("File not found.");
        }

        ShareTokenEntity token;
        if (fileEntity.passwordHash != null && !fileEntity.passwordHash.isEmpty()) {
            String sessionToken = (String) request.getSession().getAttribute("file-session-token");
            if (sessionToken == null || !sessionService.validateFileSessionToken(sessionToken, uuid)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            token = fileService.generateShareToken(uuid, expirationDate, sessionToken, numberOfDownloads);
        } else {
            token = fileService.generateShareToken(uuid, expirationDate, numberOfDownloads);
        }
        String shareLink = FileUtils.getShareLink(request, token.shareToken);
        return ok(shareLink);
    }

    @GetMapping("/download/{uuid}/{token}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String uuid, @PathVariable String token, HttpServletRequest request) {
        try {
            StreamingResponseBody responseBody = fileService.streamFileAndUpdateToken(uuid, token, request);
            if (responseBody == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            FileEntity fileEntity = fileService.getFile(uuid);

            return ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileEntity.name + "\"")
                    .header("Content-Type", "application/octet-stream")
                    .body(responseBody);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
