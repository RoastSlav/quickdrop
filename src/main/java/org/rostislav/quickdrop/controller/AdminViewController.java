package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.*;
import org.rostislav.quickdrop.model.*;
import org.rostislav.quickdrop.service.*;
import org.rostislav.quickdrop.util.FileUtils;
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

import static org.rostislav.quickdrop.util.FileUtils.*;

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
    private final FileQueryService fileQueryService;
    private final FileLifecycleService fileLifecycleService;
    private final PasteService pasteService;
    private final SessionService sessionService;
    private final SystemInfoService systemInfoService;
    private final NotificationService notificationService;

    public AdminViewController(ApplicationSettingsService applicationSettingsService,
                               AnalyticsService analyticsService,
                               FileQueryService fileQueryService,
                               FileLifecycleService fileLifecycleService,
                               PasteService pasteService,
                               SessionService sessionService,
                               SystemInfoService systemInfoService,
                               NotificationService notificationService) {
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.fileQueryService = fileQueryService;
        this.fileLifecycleService = fileLifecycleService;
        this.pasteService = pasteService;
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
                               @RequestParam(name = "deleted", defaultValue = "false") boolean showDeleted,
                               Model model) {
        int pageNumber = clampPage(page);
        int pageSize = clampSize(size);

        Page<FileEntityView> filesPage = showDeleted
                ? fileQueryService.getDeletedFilesWithDownloadCounts(PageRequest.of(pageNumber, pageSize), query)
                : fileQueryService.getFilesWithDownloadCounts(PageRequest.of(pageNumber, pageSize), query);
        model.addAttribute("filesPage", filesPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("showDeleted", showDeleted);

        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);

        return "admin-files";
    }

    @GetMapping("/pastes")
    public String getPastesPage(@RequestParam(name = "page", defaultValue = "0") int page,
                                @RequestParam(name = "size", defaultValue = "20") int size,
                                @RequestParam(name = "query", required = false) String query,
                                @RequestParam(name = "deleted", defaultValue = "false") boolean showDeleted,
                                Model model) {
        int pageNumber = clampPage(page);
        int pageSize = clampSize(size);

        Page<PasteEntityView> pastesPage = showDeleted
                ? pasteService.getDeletedPaginatedPastes(PageRequest.of(pageNumber, pageSize), query)
                : pasteService.getPaginatedPastes(PageRequest.of(pageNumber, pageSize), query);
        model.addAttribute("pastesPage", pastesPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("showDeleted", showDeleted);

        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);

        return "admin-pastes";
    }

    @GetMapping("/pastes/{uuid}/history")
    public String getPasteHistoryPage(@PathVariable String uuid, Model model) {
        Upload fileEntity = fileQueryService.getFile(uuid).orElse(null);
        if (fileEntity == null || !(fileEntity instanceof Paste)) {
            return "redirect:/admin/pastes";
        }
        long totalViews = analyticsService.getTotalViewsByPaste(uuid);
        PasteEntityView pasteView = new PasteEntityView(fileEntity, totalViews);

        List<ActivityLogEntry> actionLogs = analyticsService.getHistoryByFile(uuid)
                .stream()
                .map(ActivityLogEntry::new)
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
        // Guard: if admin password is already set, refuse to overwrite via unauthenticated POST
        if (applicationSettingsService.isAdminPasswordSet()) {
            return "redirect:/admin/dashboard";
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            return "redirect:setup";
        }
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
                               @RequestParam(value = "adminPassword", required = false) String adminPassword,
                               HttpServletRequest request) {
        String validationError = applySettingsPreprocessing(settings, request);
        if (validationError != null) {
            return "redirect:settings?error=" + validationError;
        }
        applicationSettingsService.updateApplicationSettings(settings, settings.getAppPassword(), appLogo, clearLogo);
        if (adminPassword != null && !adminPassword.isBlank()) {
            applicationSettingsService.setAdminPassword(adminPassword);
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request);
        analyticsService.logEvent(EventType.ADMIN_SETTINGS_CHANGE, info.ipAddress(), info.userAgent());
        return "redirect:settings";
    }

    @PostMapping("/api/save")
    @ResponseBody
    public ResponseEntity<String> saveSettingsApi(ApplicationSettingsViewModel settings,
                                                  @RequestParam(value = "appLogo", required = false) MultipartFile appLogo,
                                                  @RequestParam(value = "clearLogo", required = false, defaultValue = "false") boolean clearLogo,
                                                  @RequestParam(value = "adminPassword", required = false) String adminPassword,
                                                  HttpServletRequest request) {
        String validationError = applySettingsPreprocessing(settings, request);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationErrorMessage(validationError));
        }
        applicationSettingsService.updateApplicationSettings(settings, settings.getAppPassword(), appLogo, clearLogo);
        if (adminPassword != null && !adminPassword.isBlank()) {
            applicationSettingsService.setAdminPassword(adminPassword);
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request);
        analyticsService.logEvent(EventType.ADMIN_SETTINGS_CHANGE, info.ipAddress(), info.userAgent());
        return ResponseEntity.ok("Settings saved");
    }

    /**
     * Validates and normalises the submitted settings before they're persisted. Returns
     * {@code null} when valid, or an error code consumed by both callers above
     * ({@code saveSettings} redirects with {@code ?error=<code>}, {@code saveSettingsApi}
     * maps it to a message via {@link #validationErrorMessage}).
     */
    private String applySettingsPreprocessing(ApplicationSettingsViewModel settings, HttpServletRequest request) {
        settings.setMaxFileSize(megabytesToBytes(settings.getMaxFileSize()));
        if (request.getParameter("maxPreviewSizeBytes") != null) {
            try {
                long previewMb = Long.parseLong(request.getParameter("maxPreviewSizeBytes"));
                settings.setMaxPreviewSizeBytes(previewMb * 1024 * 1024);
            } catch (NumberFormatException ignored) {
            }
        }

        if (settings.getMaxFileSize() <= 0) {
            return "invalidMaxFileSize";
        }
        if (settings.getMaxFileLifeTime() < 1) {
            return "invalidRetention";
        }
        String storagePath = settings.getFileStoragePath();
        if (storagePath == null || storagePath.isBlank() || !isSafeStoragePath(storagePath)) {
            return "invalidStoragePath";
        }

        try {
            CronExpression.parse(settings.getFileDeletionCron());
        } catch (IllegalArgumentException ex) {
            return "invalidCron";
        }

        if (settings.getMaxBackups() < 1) {
            return "invalidMaxBackups";
        }
        try {
            CronExpression.parse(settings.getBackupCron());
        } catch (IllegalArgumentException ex) {
            return "invalidBackupCron";
        }

        return null;
    }

    // Absolute paths are deliberately allowed (Docker deployments mount /app/files, and
    // dev/test tooling points this at temp directories) — only outward directory-traversal
    // and a short list of well-known OS-critical directories are rejected.
    private static final java.util.List<String> DANGEROUS_STORAGE_ROOTS = java.util.List.of(
            "c:/windows", "c:/program files", "c:/program files (x86)",
            "/etc", "/bin", "/sbin", "/usr", "/sys", "/proc", "/boot", "/dev", "/root", "/var"
    );

    /**
     * Rejects a blank/traversal-escaping path and a short list of well-known OS-critical
     * directories (e.g. {@code C:\Windows}), but otherwise allows absolute paths — Docker
     * deployments legitimately mount the storage root at an absolute path
     * (see README: {@code mount /app/db, /app/files, /app/log}), so "must be relative" would
     * reject valid production configuration, not just dangerous ones.
     */
    private boolean isSafeStoragePath(String storagePath) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(storagePath);
            String normalized = path.normalize().toString().replace('\\', '/');
            if (normalized.equals("..") || normalized.startsWith("../")) {
                return false;
            }
            String lower = normalized.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("/") || lower.matches("^[a-z]:/?$")) {
                return false; // filesystem/drive root itself
            }
            for (String dangerous : DANGEROUS_STORAGE_ROOTS) {
                if (lower.equals(dangerous) || lower.startsWith(dangerous + "/")) {
                    return false;
                }
            }
            return true;
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private String validationErrorMessage(String code) {
        return switch (code) {
            case "invalidMaxFileSize" -> "Max file size must be greater than zero";
            case "invalidRetention" -> "File retention must be at least 1 day";
            case "invalidStoragePath" -> "Storage path must be a relative path under the app directory";
            case "invalidCron" -> "Invalid cron expression";
            case "invalidMaxBackups" -> "Number of backups to keep must be at least 1";
            case "invalidBackupCron" -> "Invalid backup cron expression";
            default -> "Invalid settings";
        };
    }

    @PostMapping("/password")
    public String checkAdminPassword(@RequestParam String password, HttpServletRequest request) {
        String adminPasswordHash = applicationSettingsService.getAdminPasswordHash();
        RequesterInfo info = FileUtils.getRequesterInfo(request);

        if (adminPasswordHash == null || adminPasswordHash.isBlank()) {
            return "redirect:/admin/setup";
        }

        if (BCrypt.checkpw(password, adminPasswordHash)) {
            String adminAccessToken = sessionService.addAdminToken(UUID.randomUUID().toString());
            HttpSession session = request.getSession();
            session.setAttribute("admin-session-token", adminAccessToken);
            session.setAttribute("admin-ip", info.ipAddress());
            session.setAttribute("admin-ua", info.userAgent());
            analyticsService.logEvent(EventType.ADMIN_LOGIN, info.ipAddress(), info.userAgent());
            return "redirect:dashboard";
        } else {
            analyticsService.logEvent(EventType.ADMIN_LOGIN_FAIL, info.ipAddress(), info.userAgent());
            return "redirect:password?error";
        }
    }

    @GetMapping({"", "/"})
    public String getAdminRoot() {
        return "redirect:/admin/dashboard";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        RequesterInfo info = FileUtils.getRequesterInfo(request);
        analyticsService.logEvent(EventType.ADMIN_LOGOUT, info.ipAddress(), info.userAgent());
        sessionService.invalidateAdminSession(request);
        HttpSession session = request.getSession(false);
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
        fileLifecycleService.updateKeepIndefinitely(uuid, keepIndefinitely, request);
        return "redirect:/admin/" + safeAdminSource(source);
    }

    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid,
                               @RequestParam(defaultValue = "files") String source,
                               HttpServletRequest request) {
        fileLifecycleService.toggleHidden(uuid, request);
        return "redirect:/admin/" + safeAdminSource(source);
    }

    private static boolean isAjaxRequest(HttpServletRequest request) {
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }

    @PostMapping("/delete/{uuid}")
    public Object deleteFile(@PathVariable String uuid,
                             @RequestParam(defaultValue = "files") String source,
                             HttpServletRequest request) {
        RequesterInfo info = FileUtils.getRequesterInfo(request);
        boolean deleted = fileLifecycleService.deleteFileFromDatabaseAndFileSystem(uuid, info.ipAddress(), info.userAgent());
        if (isAjaxRequest(request)) {
            return deleted
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.internalServerError().build();
        }
        return "redirect:/admin/" + safeAdminSource(source);
    }

    /**
     * Validates the {@code source} redirect parameter used by admin mutation endpoints.
     * Only the known admin sub-pages are allowed; anything else falls back to {@code "files"}
     * to prevent open redirects within the admin namespace.
     *
     * @param source the raw {@code source} request parameter
     * @return a safe, whitelisted sub-path segment
     */
    private static String safeAdminSource(String source) {
        return java.util.Set.of("files", "pastes", "share-links").contains(source) ? source : "files";
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
        int pageNumber = clampPage(page);
        int pageSize = clampSize(size);

        Boolean isPaste = switch (type != null ? type : "") {
            case "file" -> false;
            case "paste" -> true;
            default -> null;
        };

        Sort sort = buildShareSort(sortBy, sortDir);

        Page<ShareTokenEntity> tokensPage = fileQueryService.getFilteredShareTokens(
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
        fileLifecycleService.revokeShareToken(id, request);
        return "redirect:/admin/share-links";
    }

    /**
     * Displays the global activity log with optional date-range, event-type, source-type,
     * IP, and user-agent filters.
     *
     * @param startDate  optional lower bound on event timestamp (ISO date-time string)
     * @param endDate    optional upper bound on event timestamp (ISO date-time string)
     * @param eventType  optional exact event type filter ({@link EventType} name)
     * @param ip         optional IP address substring filter
     * @param ua         optional user-agent substring filter
     * @param sourceType optional source category: {@code "file"}, {@code "paste"}, or
     *                   {@code "system"}; omit or leave blank for all
     * @param page       zero-based page index (default 0)
     * @param size       page size, clamped to [1, 100] (default 30)
     * @param model      Spring MVC model
     * @return the {@code admin-activity} template name
     */
    @GetMapping("/activity")
    public String getActivityPage(@RequestParam(required = false) String startDate,
                                  @RequestParam(required = false) String endDate,
                                  @RequestParam(required = false) String eventType,
                                  @RequestParam(required = false) String ip,
                                  @RequestParam(required = false) String ua,
                                  @RequestParam(required = false) String sourceType,
                                  @RequestParam(name = "page", defaultValue = "0") int page,
                                  @RequestParam(name = "size", defaultValue = "30") int size,
                                  Model model) {
        int pageNumber = clampPage(page);
        int pageSize = clampSize(size);

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

        EventType typeFilter = null;
        if (eventType != null && !eventType.isBlank()) {
            try {
                typeFilter = EventType.valueOf(eventType.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Normalise blank strings to null so service-layer filters treat them as "no filter".
        String ipFilter = (ip == null || ip.isBlank()) ? null : ip;
        String uaFilter = (ua == null || ua.isBlank()) ? null : ua;
        // Normalisation (lowercase, blank→null) is owned by AnalyticsService; pass raw value.
        Page<ActivityLog> activityPage = analyticsService.getFilteredActivity(
                start, end, typeFilter, ipFilter, uaFilter, sourceType, PageRequest.of(pageNumber, pageSize));

        // Resolve the display value after the service has normalised it.
        String resolvedSourceType = (sourceType == null || sourceType.isBlank()) ? "" : sourceType.toLowerCase();
        model.addAttribute("activityPage", activityPage);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("eventTypes", Arrays.asList(EventType.values()));
        model.addAttribute("selectedEventType", eventType == null ? "" : eventType);
        model.addAttribute("startDate", startDate == null ? "" : startDate);
        model.addAttribute("endDate", endDate == null ? "" : endDate);
        model.addAttribute("ip", ip == null ? "" : ip);
        model.addAttribute("ua", ua == null ? "" : ua);
        model.addAttribute("sourceType", resolvedSourceType);
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
