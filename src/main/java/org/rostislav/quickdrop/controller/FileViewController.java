package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileActionLogDTO;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.populateModelAttributes;

@Controller
@RequestMapping("/file")
public class FileViewController {
    private final FileService fileService;
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final SessionService sessionService;
    private static final Logger logger = LoggerFactory.getLogger(FileViewController.class);

    public FileViewController(FileService fileService, ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService, SessionService sessionService) {
        this.fileService = fileService;
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.sessionService = sessionService;
    }

    @GetMapping("/upload")
    public String showUploadFile(Model model) {
        model.addAttribute("maxFileSize", applicationSettingsService.getFormattedMaxFileSize());
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());
        return "upload";
    }

    @GetMapping("/list")
    public String listFiles(Model model) {
        if (!applicationSettingsService.isFileListPageEnabled()) {
            return "redirect:/";
        }

        List<FileEntity> files = fileService.getNotHiddenFiles();
        model.addAttribute("files", files);
        return "listFiles";
    }

    @GetMapping("/{uuid}")
    public String filePage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(uuid);
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());

        populateModelAttributes(fileEntity, model, request);

        boolean previewsEnabled = applicationSettingsService.isPreviewEnabled();
        boolean isImage = previewsEnabled && fileService.isPreviewableImage(fileEntity);
        boolean isText = previewsEnabled && fileService.isPreviewableText(fileEntity);
        long previewLimit = applicationSettingsService.getMaxPreviewSizeBytes();
        boolean requireManualPreview = fileEntity != null && fileEntity.size > previewLimit;
        model.addAttribute("isPreviewEnabled", previewsEnabled);
        model.addAttribute("isPreviewableImage", isImage);
        model.addAttribute("isPreviewableText", isText);
        model.addAttribute("previewUrl", "/file/preview/" + uuid);
        model.addAttribute("requireManualPreview", requireManualPreview);
        model.addAttribute("maxPreviewSizeMB", previewLimit / 1024 / 1024);

        return "fileView";
    }

    @GetMapping("/preview/{uuid}")
    public ResponseEntity<StreamingResponseBody> previewFile(@PathVariable String uuid, HttpServletRequest request,
                                                             @RequestParam(name = "manual", defaultValue = "false") boolean manual) {
        return fileService.previewFile(uuid, request, manual);
    }

    @PostMapping("/download/log/{uuid}")
    public ResponseEntity<Void> logDownload(@PathVariable String uuid, HttpServletRequest request) {
        if (!fileService.isAuthorizedForFile(uuid, request)) {
            return ResponseEntity.status(403).build();
        }
        fileService.logDownload(uuid, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history/{uuid}")
    public String viewFileHistory(@PathVariable String uuid, Model model) {
        FileEntity fileEntity = fileService.getFile(uuid);
        long totalDownloads = analyticsService.getTotalDownloadsByFile(uuid);
        FileEntityView fileEntityView = new FileEntityView(fileEntity, totalDownloads);

        List<FileActionLogDTO> actionLogs = analyticsService.getHistoryByFile(uuid)
            .stream()
            .map(FileActionLogDTO::new)
            .toList();

        model.addAttribute("file", fileEntityView);
        model.addAttribute("actionLogs", actionLogs);

        return "file-history";
    }


    @PostMapping("/password")
    public String checkPassword(String uuid, String password, HttpServletRequest request, Model model) {
        if (fileService.checkFilePassword(uuid, password)) {
            String fileSessionToken = sessionService.addFileSessionToken(UUID.randomUUID().toString(), password, uuid);
            HttpSession session = request.getSession();
            session.setAttribute("file-session-token", fileSessionToken);
            logger.info("Token has been added to the session for file UUID: {}", uuid);
            return "redirect:/file/" + uuid;
        } else {
            logger.info("Incorrect password attempt for file UUID: {}", uuid);
            model.addAttribute("uuid", uuid);
            FileEntity fileEntity = fileService.getFile(uuid);
            if (fileEntity != null) {
                model.addAttribute("fileName", fileEntity.name);
            }
            return "file-password";
        }
    }

    @GetMapping("/password/{uuid}")
    public String passwordPage(@PathVariable String uuid, Model model) {
        model.addAttribute("uuid", uuid);
        FileEntity fileEntity = fileService.getFile(uuid);
        if (fileEntity != null) {
            model.addAttribute("fileName", fileEntity.name);
        }
        return "file-password";
    }

    @GetMapping("/download/{uuid}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String uuid, HttpServletRequest request) {
        return fileService.downloadFile(uuid, request);
    }

    @PostMapping("/extend/{uuid}")
    public String extendFile(@PathVariable String uuid, Model model, HttpServletRequest request) {
        fileService.extendFile(uuid, request);

        FileEntity fileEntity = fileService.getFile(uuid);
        populateModelAttributes(fileEntity, model, request);
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());
        return "fileView";
    }

    @PostMapping("/delete/{uuid}")
    public String deleteFile(@PathVariable String uuid) {
        if (fileService.deleteFileFromDatabaseAndFileSystem(uuid)) {
            return "redirect:/file/list";
        } else {
            return "redirect:/file/" + uuid;
        }
    }

    @GetMapping("/search")
    public String searchFiles(String query, Model model) {
        List<FileEntity> files = fileService.searchNotHiddenFiles(query);
        model.addAttribute("files", files);
        return "listFiles";
    }

    @PostMapping("/keep-indefinitely/{uuid}")
    public String updateKeepIndefinitely(@PathVariable String uuid, @RequestParam(required = false, defaultValue = "false") boolean keepIndefinitely, HttpServletRequest request) {
        FileEntity fileEntity = fileService.updateKeepIndefinitely(uuid, keepIndefinitely, request);
        if (fileEntity != null) {
            logger.info("Updated keep indefinitely for file UUID: {} to {}", uuid, keepIndefinitely);
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }


    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid, HttpServletRequest request) {
        FileEntity fileEntity = fileService.toggleHidden(uuid, request);
        if (fileEntity != null) {
            logger.info("Updated hidden for file UUID: {} to {}", uuid, fileEntity.hidden);
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }
}
