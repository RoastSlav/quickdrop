package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.DownloadLog;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.FileRenewalLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileActionLogDTO;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.populateModelAttributes;

@Controller
@RequestMapping("/file")
public class FileViewController {
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

        return "fileView";
    }

    @GetMapping("/history/{uuid}")
    public String viewFileHistory(@PathVariable String uuid, Model model) {
        FileEntity fileEntity = fileService.getFile(uuid);
        long totalDownloads = analyticsService.getTotalDownloadsByFile(uuid);
        FileEntityView fileEntityView = new FileEntityView(fileEntity, totalDownloads);

        List<FileActionLogDTO> actionLogs = new ArrayList<>();

        List<DownloadLog> downloadLogs = analyticsService.getDownloadsByFile(uuid);
        List<FileRenewalLog> renewalLogs = analyticsService.getRenewalLogsByFile(uuid);
        downloadLogs.forEach(log -> actionLogs.add(new FileActionLogDTO(log)));
        renewalLogs.forEach(log -> actionLogs.add(new FileActionLogDTO(log)));

        actionLogs.sort(Comparator.comparing(FileActionLogDTO::getActionDate).reversed());

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
            return "redirect:/file/" + uuid;
        } else {
            model.addAttribute("uuid", uuid);
            return "file-password";
        }
    }

    @GetMapping("/password/{uuid}")
    public String passwordPage(@PathVariable String uuid, Model model) {
        model.addAttribute("uuid", uuid);
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
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }


    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid) {
        FileEntity fileEntity = fileService.toggleHidden(uuid);
        if (fileEntity != null) {
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }

    @GetMapping("/share/{token}")
    public String viewSharedFile(@PathVariable String token, Model model) {
        ShareTokenEntity tokenEntity = fileService.getShareTokenEntityByToken(token);

        if (!fileService.validateShareToken(tokenEntity)) {
            return "invalid-share-link";
        }

        FileEntity file = fileService.getFile(tokenEntity.file.uuid);
        if (file == null) {
            return "redirect:/file/list";
        }

        model.addAttribute("file", new FileEntityView(file, analyticsService.getTotalDownloadsByFile(file.uuid)));
        model.addAttribute("downloadLink", "/api/file/download/" + file.uuid + "/" + token);

        return "file-share-view";
    }
}
