package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.*;
import org.rostislav.quickdrop.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.bytesToMegabytes;
import static org.rostislav.quickdrop.util.FileUtils.megabytesToBytes;

@Controller
@RequestMapping("/admin")
public class AdminViewController {
    private static final Logger logger = LoggerFactory.getLogger(AdminViewController.class);
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final FileService fileService;
    private final SessionService sessionService;
    private final SystemInfoService systemInfoService;
    private final NotificationService notificationService;

    public AdminViewController(ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService, FileService fileService, SessionService sessionService, SystemInfoService systemInfoService, NotificationService notificationService) {
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.fileService = fileService;
        this.sessionService = sessionService;
        this.systemInfoService = systemInfoService;
        this.notificationService = notificationService;
    }

    @GetMapping("/dashboard")
    public String getDashboardPage(Model model) {
        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);
        model.addAttribute("isAdminDashboardPage", true);
        return "dashboard";
    }

    @GetMapping("/files")
    public String getFilesPage(@RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "20") int size,
                               @RequestParam(name = "query", required = false) String query,
                               Model model) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        Page<FileEntityView> filesPage = fileService.getFilesWithDownloadCounts(PageRequest.of(pageNumber, pageSize), query);
        model.addAttribute("filesPage", filesPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("query", query == null ? "" : query);

        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);

        return "admin-files";
    }

    @GetMapping("/pastes")
    public String getPastesPage(@RequestParam(name = "page", defaultValue = "0") int page,
                                @RequestParam(name = "size", defaultValue = "20") int size,
                                @RequestParam(name = "query", required = false) String query,
                                Model model) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        Page<PasteEntityView> pastesPage = fileService.getPaginatedPastes(PageRequest.of(pageNumber, pageSize), query);
        model.addAttribute("pastesPage", pastesPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("query", query == null ? "" : query);

        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);

        return "admin-pastes";
    }

    @GetMapping("/pastes/{uuid}/history")
    public String getPasteHistoryPage(@PathVariable String uuid, Model model) {
        var fileEntity = fileService.getFile(uuid);
        if (fileEntity == null || !fileEntity.paste) {
            return "redirect:/admin/pastes";
        }
        long totalViews = analyticsService.getTotalViewsByPaste(uuid);
        PasteEntityView pasteView = new PasteEntityView(fileEntity, totalViews);

        List<FileActionLogDTO> actionLogs = analyticsService.getHistoryByFile(uuid)
                .stream()
                .map(FileActionLogDTO::new)
                .toList();

        model.addAttribute("paste", pasteView);
        model.addAttribute("actionLogs", actionLogs);
        return "admin-paste-history";
    }

    @GetMapping("/setup")
    public String showSetupPage() {
        if (applicationSettingsService.isAdminPasswordSet()) {
            return "redirect:dashboard";
        }
        return "welcome";
    }

    @PostMapping("/setup")
    public String setAdminPassword(String adminPassword) {
        applicationSettingsService.setAdminPassword(adminPassword);
        return "redirect:dashboard";
    }

    @GetMapping("/settings")
    public String getSettingsPage(@RequestParam(value = "error", required = false) String error, Model model) {
        ApplicationSettingsEntity settings = applicationSettingsService.getApplicationSettings();

        ApplicationSettingsViewModel applicationSettingsViewModel = new ApplicationSettingsViewModel(settings);
        applicationSettingsViewModel.setMaxFileSize(bytesToMegabytes(settings.getMaxFileSize()));

        model.addAttribute("settings", applicationSettingsViewModel);
        model.addAttribute("aboutInfo", systemInfoService.getAboutInfo());

        try {
            var cron = CronExpression.parse(settings.getFileDeletionCron());
            var next = cron.next(java.time.LocalDateTime.now());
            String nextText = next != null ? next.toString() : "No upcoming run";
            model.addAttribute("cronNextRunText", nextText);
        } catch (IllegalArgumentException e) {
            model.addAttribute("cronNextRunText", "Invalid cron expression");
        }

        if (error != null) {
            model.addAttribute("error", error);
        }
        return "settings";
    }

    @PostMapping("/save")
    public String saveSettings(ApplicationSettingsViewModel settings,
                               @RequestParam(value = "appLogo", required = false) MultipartFile appLogo,
                               @RequestParam(value = "clearLogo", required = false, defaultValue = "false") boolean clearLogo,
                               HttpServletRequest request) {
        settings.setMaxFileSize(megabytesToBytes(settings.getMaxFileSize()));
        if (request.getParameter("maxPreviewSizeBytes") != null) {
            try {
                long previewMb = Long.parseLong(request.getParameter("maxPreviewSizeBytes"));
                settings.setMaxPreviewSizeBytes(previewMb * 1024 * 1024);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            CronExpression.parse(settings.getFileDeletionCron());
        } catch (IllegalArgumentException ex) {
            return "redirect:settings?error=invalidCron";
        }
        applicationSettingsService.updateApplicationSettings(settings, settings.getAppPassword(), appLogo, clearLogo);
        return "redirect:settings";
    }

    @PostMapping("/api/save")
    @ResponseBody
    public ResponseEntity<String> saveSettingsApi(ApplicationSettingsViewModel settings,
                                                  @RequestParam(value = "appLogo", required = false) MultipartFile appLogo,
                                                  @RequestParam(value = "clearLogo", required = false, defaultValue = "false") boolean clearLogo,
                                                  HttpServletRequest request) {
        settings.setMaxFileSize(megabytesToBytes(settings.getMaxFileSize()));
        if (request.getParameter("maxPreviewSizeBytes") != null) {
            try {
                long previewMb = Long.parseLong(request.getParameter("maxPreviewSizeBytes"));
                settings.setMaxPreviewSizeBytes(previewMb * 1024 * 1024);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            CronExpression.parse(settings.getFileDeletionCron());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body("Invalid cron expression");
        }
        applicationSettingsService.updateApplicationSettings(settings, settings.getAppPassword(), appLogo, clearLogo);
        return ResponseEntity.ok("Settings saved");
    }

    @PostMapping("/password")
    public String checkAdminPassword(@RequestParam String password, HttpServletRequest request) {
        String adminPasswordHash = applicationSettingsService.getAdminPasswordHash();

        if (BCrypt.checkpw(password, adminPasswordHash)) {
            String adminAccessToken = sessionService.addAdminToken(UUID.randomUUID().toString());
            HttpSession session = request.getSession();
            session.setAttribute("admin-session-token", adminAccessToken);
            return "redirect:dashboard";
        } else {
            return "redirect:password";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        sessionService.invalidateAdminSession(request);
        try {
            request.logout();
        } catch (Exception ignored) {
        }
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/";
    }

    @GetMapping("/password")
    public String showAdminPasswordPage() {
        return "admin-password";
    }

    @PostMapping("/keep-indefinitely/{uuid}")
    public String updateKeepIndefinitely(@PathVariable String uuid,
                                         @RequestParam(required = false, defaultValue = "false") boolean keepIndefinitely,
                                         @RequestParam(defaultValue = "files") String source,
                                         HttpServletRequest request) {
        fileService.updateKeepIndefinitely(uuid, keepIndefinitely, request);
        return "redirect:/admin/" + source;
    }

    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid,
                               @RequestParam(defaultValue = "files") String source,
                               HttpServletRequest request) {
        fileService.toggleHidden(uuid, request);
        return "redirect:/admin/" + source;
    }

    @PostMapping("/delete/{uuid}")
    public String deleteFile(@PathVariable String uuid,
                             @RequestParam(defaultValue = "files") String source) {
        fileService.deleteFileFromDatabaseAndFileSystem(uuid);
        return "redirect:/admin/" + source;
    }

    @PostMapping("/notification-test")
    @ResponseBody
    public ResponseEntity<String> sendNotificationTest(@RequestParam String target) {
        return switch (target.toLowerCase()) {
            case "discord" -> {
                var result = notificationService.sendTestDiscord();
                if (result.success()) {
                    yield ResponseEntity.ok(result.message());
                }
                logger.warn("Discord test notification failed: {}", result.message());
                yield ResponseEntity.badRequest().body(result.message());
            }
            case "email" -> {
                var result = notificationService.sendTestEmail();
                if (result.success()) {
                    yield ResponseEntity.ok(result.message());
                }
                logger.warn("Email test notification failed: {}", result.message());
                yield ResponseEntity.badRequest().body(result.message());
            }
            default -> ResponseEntity.badRequest().body("Unknown notification target.");
        };
    }
}
