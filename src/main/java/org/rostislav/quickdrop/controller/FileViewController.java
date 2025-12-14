package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileActionLogDTO;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

import java.util.List;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.*;

@Controller
@RequestMapping("/file")
public class FileViewController {
    private static final Logger logger = LoggerFactory.getLogger(FileViewController.class);
    private final FileService fileService;
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final SessionService sessionService;

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
    public String listFiles(@RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size", defaultValue = "20") int size,
                            @RequestParam(name = "query", required = false) String query,
                            Model model) {
        if (!applicationSettingsService.isFileListPageEnabled()) {
            return "redirect:/";
        }

        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        Page<FileEntity> filesPage = fileService.getVisibleFiles(PageRequest.of(pageNumber, pageSize), query);
        model.addAttribute("filesPage", filesPage);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("pageSize", pageSize);
        return "listFiles";
    }

    @GetMapping("/{uuid}")
    public String filePage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(uuid);
        if (fileEntity == null) {
            logger.info("File not found for UUID: {}", uuid);
            return "redirect:/file/list";
        }

        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());

        populateModelAttributes(fileEntity, model, request);

        boolean previewsEnabled = applicationSettingsService.isPreviewEnabled();
        boolean isImage = previewsEnabled && isPreviewableImage(fileEntity);
        boolean isText = previewsEnabled && isPreviewableText(fileEntity);
        boolean isPdf = previewsEnabled && isPreviewablePdf(fileEntity);
        boolean isJson = previewsEnabled && isPreviewableJson(fileEntity);
        boolean isCsv = previewsEnabled && isPreviewableCsvOrTsv(fileEntity);

        String previewType = determinePreviewType(isImage, isPdf, isJson, isCsv, isText);
        long previewLimit = applicationSettingsService.getMaxPreviewSizeBytes();
        boolean requireManualPreview = fileEntity.size > previewLimit;

        model.addAttribute("isPreviewEnabled", previewsEnabled);
        model.addAttribute("isPreviewableImage", isImage);
        model.addAttribute("isPreviewableText", isText);
        model.addAttribute("isPreviewablePdf", isPdf);
        model.addAttribute("isPreviewableJson", isJson);
        model.addAttribute("isPreviewableCsv", isCsv);
        model.addAttribute("previewType", previewType);
        model.addAttribute("previewUrl", String.format("/file/preview/%s", uuid));
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
    public String checkPassword(@RequestParam("uuid") String uuid,
                                @RequestParam("password") String password,
                                HttpServletRequest request,
                                org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        if (fileService.checkFilePassword(uuid, password)) {
            String fileSessionToken = sessionService.addFileSessionToken(UUID.randomUUID().toString(), password, uuid);
            request.getSession().setAttribute("file-session-token", fileSessionToken);
            logger.info("Token has been added to the session for file UUID: {}", uuid);
            return "redirect:/file/" + uuid;
        } else {
            logger.info("Incorrect password attempt for file UUID: {}", uuid);
            redirectAttributes.addFlashAttribute("passwordError", true);
            return "redirect:/file/password/" + uuid;
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
    public String extendFile(@PathVariable String uuid, HttpServletRequest request) {
        fileService.extendFile(uuid, request);
        return "redirect:/file/" + uuid;
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
    public String searchFiles(@RequestParam String query,
                              @RequestParam(name = "size", defaultValue = "20") int size) {
        if (query == null || query.isBlank()) {
            return "redirect:/file/list";
        }
        int pageSize = Math.min(Math.max(size, 1), 100);
        String encodedQuery = UriUtils.encodeQueryParam(query, java.nio.charset.StandardCharsets.UTF_8);
        return "redirect:/file/list?query=" + encodedQuery + "&page=0&size=" + pageSize;
    }

    @PostMapping("/keep-indefinitely/{uuid}")
    public String updateKeepIndefinitely(@PathVariable String uuid,
                                         @RequestParam(required = false, defaultValue = "false") boolean keepIndefinitely,
                                         HttpServletRequest request) {
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
