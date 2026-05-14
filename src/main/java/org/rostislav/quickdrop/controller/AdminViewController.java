package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.*;
import org.rostislav.quickdrop.repository.FileHistoryLogRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.bytesToMegabytes;
import static org.rostislav.quickdrop.util.FileUtils.megabytesToBytes;

/**
 * Handles all admin UI pages and admin-only actions under {@code /admin}.
 *
 * <p>Access to every endpoint in this controller is guarded by
 * {@link org.rostislav.quickdrop.interceptor.AdminPasswordInterceptor}, which
 * redirects unauthenticated requests to the admin login page.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Dashboard analytics display</li>
 *   <li>Paginated file and paste management (list, delete, hide, keep-indefinitely)</li>
 *   <li>Paste history views</li>
 *   <li>Application settings read and write (form and JSON API variants)</li>
 *   <li>Admin password setup and login/logout</li>
 *   <li>On-demand notification tests (Discord / email)</li>
 * </ul>
 */
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
    private final ShareTokenRepository shareTokenRepository;
    private final FileHistoryLogRepository fileHistoryLogRepository;

    public AdminViewController(ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService, FileService fileService, SessionService sessionService, SystemInfoService systemInfoService, NotificationService notificationService, ShareTokenRepository shareTokenRepository, FileHistoryLogRepository fileHistoryLogRepository) {
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.fileService = fileService;
        this.sessionService = sessionService;
        this.systemInfoService = systemInfoService;
        this.notificationService = notificationService;
        this.shareTokenRepository = shareTokenRepository;
        this.fileHistoryLogRepository = fileHistoryLogRepository;
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

    /**
     * Displays the active share links admin page with search, filters, sort, and pagination.
     *
     * @param page      zero-based page index (default 0)
     * @param size      page size, clamped to [1, 100] (default 20)
     * @param query     optional search string matched against file name and token string
     * @param type      optional type filter: {@code "file"}, {@code "paste"}, or omitted for all
     * @param noExpiry  when {@code true} show only tokens with no expiry date
     * @param unlimited when {@code true} show only tokens with no download cap
     * @param sortBy    sort field: {@code "created"} (default), {@code "name"},
     *                  {@code "expiry"}, {@code "downloads"}
     * @param sortDir   sort direction: {@code "desc"} (default) or {@code "asc"}
     * @param model     Spring MVC model
     * @return the {@code admin-share-links} template name
     */
    @GetMapping("/share-links")
    public String getShareLinksPage(@RequestParam(name = "page", defaultValue = "0") int page,
                                    @RequestParam(name = "size", defaultValue = "20") int size,
                                    @RequestParam(required = false) String query,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(defaultValue = "false") boolean noExpiry,
                                    @RequestParam(defaultValue = "false") boolean unlimited,
                                    @RequestParam(defaultValue = "created") String sortBy,
                                    @RequestParam(defaultValue = "desc") String sortDir,
                                    Model model) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        Boolean isPaste = switch (type != null ? type : "") {
            case "file" -> false;
            case "paste" -> true;
            default -> null;
        };

        Sort sort = buildShareSort(sortBy, sortDir);

        Page<ShareTokenEntity> tokensPage = shareTokenRepository.findFiltered(
                LocalDate.now(), isPaste, noExpiry, unlimited,
                (query == null || query.isBlank()) ? null : query,
                PageRequest.of(pageNumber, pageSize, sort));

        model.addAttribute("tokensPage", tokensPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("type", type == null ? "" : type);
        model.addAttribute("noExpiry", noExpiry);
        model.addAttribute("unlimited", unlimited);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortDir", sortDir);
        model.addAttribute("totalActive", tokensPage.getTotalElements());
        return "admin-share-links";
    }

    /**
     * Builds a {@link Sort} for the share-links page from the user-supplied field name
     * and direction string.
     *
     * @param sortBy  field token: {@code "created"}, {@code "name"}, {@code "expiry"},
     *                or {@code "downloads"}
     * @param sortDir {@code "asc"} or {@code "desc"}
     * @return the resolved {@link Sort}
     */
    private Sort buildShareSort(String sortBy, String sortDir) {
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return switch (sortBy) {
            case "name" -> Sort.by(dir, "file.name");
            case "expiry" -> Sort.by(dir, "tokenExpirationDate");
            case "downloads" -> Sort.by(dir, "numberOfAllowedDownloads");
            default -> Sort.by(dir, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        };
    }

    /**
     * Revokes a share token by ID and redirects back to the share links page.
     *
     * @param id      database ID of the share token to revoke
     * @param request the HTTP request (for history log IP/user-agent metadata)
     * @return redirect to {@code /admin/share-links}
     */
    @PostMapping("/share-links/revoke/{id}")
    public String revokeShareToken(@PathVariable Long id, HttpServletRequest request) {
        fileService.revokeShareToken(id, request);
        return "redirect:/admin/share-links";
    }

    /**
     * Displays the global activity log with optional date-range, event-type, IP, and
     * user-agent filters.
     *
     * @param startDate optional lower bound on event timestamp (ISO date-time string)
     * @param endDate   optional upper bound on event timestamp (ISO date-time string)
     * @param eventType optional exact event type filter
     * @param ip        optional IP address substring filter
     * @param ua        optional user-agent substring filter
     * @param page      zero-based page index (default 0)
     * @param size      page size, clamped to [1, 100] (default 30)
     * @param model     Spring MVC model
     * @return the {@code admin-activity} template name
     */
    @GetMapping("/activity")
    public String getActivityPage(@RequestParam(required = false) String startDate,
                                  @RequestParam(required = false) String endDate,
                                  @RequestParam(required = false) String eventType,
                                  @RequestParam(required = false) String ip,
                                  @RequestParam(required = false) String ua,
                                  @RequestParam(name = "page", defaultValue = "0") int page,
                                  @RequestParam(name = "size", defaultValue = "30") int size,
                                  Model model) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);

        LocalDateTime start = null;
        LocalDateTime end = null;
        try {
            if (startDate != null && !startDate.isBlank()) start = LocalDateTime.parse(startDate);
        } catch (Exception ignored) {
        }
        try {
            if (endDate != null && !endDate.isBlank()) end = LocalDateTime.parse(endDate);
        } catch (Exception ignored) {
        }

        FileHistoryType typeFilter = null;
        if (eventType != null && !eventType.isBlank()) {
            try {
                typeFilter = FileHistoryType.valueOf(eventType.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        String ipFilter = (ip != null && ip.isBlank()) ? null : ip;
        String uaFilter = (ua != null && ua.isBlank()) ? null : ua;

        Page<FileHistoryLog> activityPage = fileHistoryLogRepository.findFiltered(
                start, end, typeFilter, ipFilter, uaFilter, PageRequest.of(pageNumber, pageSize));

        model.addAttribute("activityPage", activityPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("eventTypes", Arrays.asList(FileHistoryType.values()));
        model.addAttribute("selectedEventType", eventType == null ? "" : eventType);
        model.addAttribute("startDate", startDate == null ? "" : startDate);
        model.addAttribute("endDate", endDate == null ? "" : endDate);
        model.addAttribute("ip", ip == null ? "" : ip);
        model.addAttribute("ua", ua == null ? "" : ua);
        return "admin-activity";
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
