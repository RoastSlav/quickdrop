package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.DownloadLog;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.repository.DownloadLogRepository;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;
import static org.rostislav.quickdrop.util.FileUtils.populateModelAttributes;

@Controller
@RequestMapping("/file")
public class FileViewController {
    private final FileService fileService;
    private final ApplicationSettingsService applicationSettingsService;
    private final DownloadLogRepository downloadLogRepository;
    private final AnalyticsService analyticsService;

    public FileViewController(FileService fileService, ApplicationSettingsService applicationSettingsService, DownloadLogRepository downloadLogRepository, AnalyticsService analyticsService) {
        this.fileService = fileService;
        this.applicationSettingsService = applicationSettingsService;
        this.downloadLogRepository = downloadLogRepository;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/upload")
    public String showUploadFile(Model model) {
        model.addAttribute("maxFileSize", applicationSettingsService.getFormattedMaxFileSize());
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());
        return "upload";
    }

    @GetMapping("/list")
    public String listFiles(Model model) {
        List<FileEntity> files = fileService.getNotHiddenFiles();
        model.addAttribute("files", files);
        return "listFiles";
    }

    @GetMapping("/{uuid}")
    public String filePage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(uuid);
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());

        String password = (String) request.getSession().getAttribute("password");
        if (fileEntity.passwordHash != null &&
                (password == null || !fileService.checkPassword(uuid, password))) {
            model.addAttribute("uuid", uuid);
            return "file-password";
        }

        populateModelAttributes(fileEntity, model, request);

        return "fileView";
    }

    @GetMapping("/history/{id}")
    public String viewDownloadHistory(@PathVariable Long id, Model model, HttpServletRequest request) {
        if (!applicationSettingsService.checkForAdminPassword(request)) {
            return "redirect:/admin/password";
        }

        FileEntity file = fileService.getFile(id);
        List<DownloadLog> downloadHistory = downloadLogRepository.findByFileId(id);
        long totalDownloads = analyticsService.getTotalDownloadsByFile(id);

        model.addAttribute("file", new FileEntityView(file, formatFileSize(file.size), totalDownloads));
        model.addAttribute("downloadHistory", downloadHistory);

        return "admin/download-history";
    }


    @PostMapping("/password")
    public String checkPassword(String uuid, String password, HttpServletRequest request, Model model) {
        if (fileService.checkPassword(uuid, password)) {
            request.getSession().setAttribute("password", password);
            return "redirect:/file/" + uuid;
        } else {
            model.addAttribute("uuid", uuid);
            return "file-password";
        }
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable Long id, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(id);

        if (fileEntity.passwordHash != null) {
            String password = (String) request.getSession().getAttribute("password");
            if (password == null || !fileService.checkPassword(fileEntity.uuid, password)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        String password = (String) request.getSession().getAttribute("password");
        return fileService.downloadFile(id, password, request);
    }

    @PostMapping("/extend/{id}")
    public String extendFile(@PathVariable Long id, Model model, HttpServletRequest request) {
        fileService.extendFile(id);

        FileEntity fileEntity = fileService.getFile(id);
        populateModelAttributes(fileEntity, model, request);
        return "fileView";
    }

    @PostMapping("/delete/{id}")
    public String deleteFile(@PathVariable Long id) {
        if (fileService.deleteFile(id)) {
            return "redirect:/file/list";
        } else {
            return "redirect:/file/" + id;
        }
    }

    @GetMapping("/search")
    public String searchFiles(String query, Model model) {
        List<FileEntity> files = fileService.searchNotHiddenFiles(query);
        model.addAttribute("files", files);
        return "listFiles";
    }

    @PostMapping("/keep-indefinitely/{id}")
    public String updateKeepIndefinitely(@PathVariable Long id, @RequestParam(required = false, defaultValue = "false") boolean keepIndefinitely, HttpServletRequest request, Model model) {
        return handlePasswordValidationAndRedirect(id, request, model, () -> fileService.updateKeepIndefinitely(id, keepIndefinitely));
    }


    @PostMapping("/toggle-hidden/{id}")
    public String toggleHidden(@PathVariable Long id, HttpServletRequest request, Model model) {
        return handlePasswordValidationAndRedirect(id, request, model, () -> fileService.toggleHidden(id));
    }


    private String handlePasswordValidationAndRedirect(Long fileId, HttpServletRequest request, Model model, Runnable action) {
        String referer = request.getHeader("Referer");

        // Check for admin password
        if (applicationSettingsService.checkForAdminPassword(request)) {
            action.run();
            return "redirect:" + referer;
        }

        // Check for file password in the session
        String filePassword = (String) request.getSession().getAttribute("password");
        if (filePassword != null) {
            FileEntity fileEntity = fileService.getFile(fileId);
            // Validate file password if the file is password-protected
            if (fileEntity.passwordHash != null && !fileService.checkPassword(fileEntity.uuid, filePassword)) {
                model.addAttribute("uuid", fileEntity.uuid);
                return "file-password"; // Redirect to file password page if the password is incorrect
            }

            action.run();
            return "redirect:" + referer;
        }

        // No valid password found, determine the redirect destination
        if (referer != null && referer.contains("/admin/dashboard")) {
            return "redirect:/admin/password"; // Redirect to admin password page
        } else {
            // Get the file for adding the UUID to the model for the file password page
            FileEntity fileEntity = fileService.getFile(fileId);
            model.addAttribute("uuid", fileEntity.uuid);
            return "file-password";
        }
    }
}
