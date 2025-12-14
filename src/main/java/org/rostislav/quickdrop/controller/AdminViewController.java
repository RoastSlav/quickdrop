package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.bytesToMegabytes;
import static org.rostislav.quickdrop.util.FileUtils.megabytesToBytes;

@Controller
@RequestMapping("/admin")
public class AdminViewController {
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
    public String getDashboardPage(@RequestParam(name = "page", defaultValue = "0") int page,
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

        return "dashboard";
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
    public Object saveSettings(ApplicationSettingsViewModel settings, HttpServletRequest request) {
        settings.setMaxFileSize(megabytesToBytes(settings.getMaxFileSize()));
        if (request.getParameter("maxPreviewSizeBytes") != null) {
            try {
                long previewMb = Long.parseLong(request.getParameter("maxPreviewSizeBytes"));
                settings.setMaxPreviewSizeBytes(previewMb * 1024 * 1024);
            } catch (NumberFormatException ignored) {
                // leave as-is if invalid
            }
        }

        try {
            CronExpression.parse(settings.getFileDeletionCron());
        } catch (IllegalArgumentException ex) {
            if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
                return ResponseEntity.badRequest().body("Invalid cron expression");
            }
            return "redirect:settings?error=invalidCron";
        }

        applicationSettingsService.updateApplicationSettings(settings, settings.getAppPassword());
        if ("XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"))) {
            return ResponseEntity.ok("Settings saved");
        }
        return "redirect:settings";
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

    @GetMapping("/password")
    public String showAdminPasswordPage() {
        return "admin-password";
    }

    @PostMapping("/keep-indefinitely/{uuid}")
    public String updateKeepIndefinitely(@PathVariable String uuid, @RequestParam(required = false, defaultValue = "false") boolean keepIndefinitely, HttpServletRequest request) {
        fileService.updateKeepIndefinitely(uuid, keepIndefinitely, request);
        return "redirect:/admin/dashboard";
    }


    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid, HttpServletRequest request) {
        fileService.toggleHidden(uuid, request);
        return "redirect:/admin/dashboard";
    }

    @PostMapping("/delete/{uuid}")
    public String deleteFile(@PathVariable String uuid) {
        fileService.deleteFileFromDatabaseAndFileSystem(uuid);

        return "redirect:/admin/dashboard";
    }

    @PostMapping("/notification-test")
    @ResponseBody
    public ResponseEntity<String> sendNotificationTest(@RequestParam String target) {
        return switch (target.toLowerCase()) {
            case "discord" -> {
                var result = notificationService.sendTestDiscord();
                yield result.success() ? ResponseEntity.ok(result.message()) : ResponseEntity.badRequest().body(result.message());
            }
            case "email" -> {
                var result = notificationService.sendTestEmail();
                yield result.success() ? ResponseEntity.ok(result.message()) : ResponseEntity.badRequest().body(result.message());
            }
            default -> ResponseEntity.badRequest().body("Unknown notification target.");
        };
    }
}
