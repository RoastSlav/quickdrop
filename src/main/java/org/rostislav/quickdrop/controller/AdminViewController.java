package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.entity.*;
import org.rostislav.quickdrop.model.*;
import org.rostislav.quickdrop.service.*;
import org.rostislav.quickdrop.util.ActivityLogCsv;
import org.rostislav.quickdrop.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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

    /** How far ahead the dashboard warns about files due for auto-deletion. */
    private static final int EXPIRING_SOON_WINDOW_DAYS = 7;

    /** Rows fetched per round trip when streaming a CSV export. */
    private static final int EXPORT_PAGE_SIZE = 1000;

    /** Number of activity-log entries shown in the dashboard feed. */
    private static final int RECENT_ACTIVITY_LIMIT = 6;
    private static final Logger logger = LoggerFactory.getLogger(AdminViewController.class);
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final FileQueryService fileQueryService;
    private final FileLifecycleService fileLifecycleService;
    private final PasteService pasteService;
    private final SessionService sessionService;
    private final SystemInfoService systemInfoService;
    private final NotificationService notificationService;
    private final ShortLinkService shortLinkService;

    public AdminViewController(ApplicationSettingsService applicationSettingsService,
                               AnalyticsService analyticsService,
                               FileQueryService fileQueryService,
                               FileLifecycleService fileLifecycleService,
                               PasteService pasteService,
                               SessionService sessionService,
                               SystemInfoService systemInfoService,
                               NotificationService notificationService,
                               ShortLinkService shortLinkService) {
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.fileQueryService = fileQueryService;
        this.fileLifecycleService = fileLifecycleService;
        this.pasteService = pasteService;
        this.sessionService = sessionService;
        this.systemInfoService = systemInfoService;
        this.notificationService = notificationService;
        this.shortLinkService = shortLinkService;
    }

    @GetMapping("/dashboard")
    public String getDashboardPage(Model model) {
        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);
        // Signal, not just counters: what is about to happen, and what just happened.
        model.addAttribute("expiringSoonCount", fileQueryService.countFilesExpiringWithin(
                applicationSettingsService.getMaxFileLifeTime(), EXPIRING_SOON_WINDOW_DAYS));
        model.addAttribute("expiringSoonDays", EXPIRING_SOON_WINDOW_DAYS);
        model.addAttribute("recentActivity", analyticsService.getRecentActivity(RECENT_ACTIVITY_LIMIT));
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
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
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
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        analyticsService.logEvent(EventType.ADMIN_SETTINGS_CHANGE, info.ipAddress(), info.userAgent());
        return ResponseEntity.ok("Settings saved");
    }

    /**
     * Accepts a reputation provider's licence terms and enables it in one step — the only
     * path that can turn a provider on (see {@link ApplicationSettingsService#acceptReputationProviderTerms}).
     * Called by the licence-acceptance modal on the settings page, not by the general
     * settings-save form.
     *
     * @param provider one of {@code "phishing_army"}, {@code "urlhaus"}, {@code "safe_browsing"}
     * @return 200 on success, 400 if {@code provider} isn't recognised
     */
    @PostMapping("/settings/accept-reputation-terms")
    @ResponseBody
    public ResponseEntity<String> acceptReputationTerms(@RequestParam String provider, HttpServletRequest request) {
        boolean accepted = applicationSettingsService.acceptReputationProviderTerms(provider);
        if (!accepted) {
            return ResponseEntity.badRequest().body("Unknown provider");
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        analyticsService.logEvent(EventType.ADMIN_SETTINGS_CHANGE, info.ipAddress(), info.userAgent());
        return ResponseEntity.ok("Accepted");
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
            default -> "Invalid settings";
        };
    }

    @PostMapping("/password")
    public String checkAdminPassword(@RequestParam String password, HttpServletRequest request) {
        String adminPasswordHash = applicationSettingsService.getAdminPasswordHash();
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());

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
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
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
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
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
        return java.util.Set.of("files", "pastes", "links").contains(source) ? source : "files";
    }

    /**
     * Legacy URL: existing bookmarks/links to the pre-merge share-links page land on the
     * merged {@code /admin/links} page instead (defaulting to the upload-share-links tab).
     */
    @GetMapping("/share-links")
    public String redirectLegacyShareLinksPage() {
        return "redirect:/admin/links";
    }

    /**
     * Displays the merged short-links admin page — upload-share links and general-purpose
     * redirect links, switchable via {@code kind} — with search, filters, sort, and pagination.
     *
     * @param kind      {@code "share"} (default) for upload-share links, {@code "redirect"}
     *                  for URL-shortener links
     * @param page      zero-based page index (default 0)
     * @param size      page size, clamped to [1, 100] (default 20)
     * @param query     optional search string matched against file name/token (share) or
     *                  destination URL/code (redirect)
     * @param type      share-links-only: optional type filter {@code "file"}, {@code "paste"},
     *                  or omitted for all
     * @param noExpiry  when {@code true} show only links with no expiry date
     * @param unlimited when {@code true} show only links with no use cap
     * @param sortBy    sort field: {@code "created"} (default), {@code "name"} (share only),
     *                  {@code "expiry"}, {@code "downloads"}
     * @param sortDir   sort direction: {@code "desc"} (default) or {@code "asc"}
     * @param model     Spring MVC model
     * @return the {@code admin-links} template name
     */
    @GetMapping("/links")
    public String getLinksPage(@RequestParam(defaultValue = "share") String kind,
                               @RequestParam(name = "page", defaultValue = "0") int page,
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
        String trimmedQuery = (query == null || query.isBlank()) ? null : query;
        boolean isRedirect = "redirect".equals(kind);

        model.addAttribute("kind", isRedirect ? "redirect" : "share");
        // Both counts, so the kind tabs can say how many of each exist before you switch.
        long[] linkCounts = fileQueryService.countActiveLinksByKind();
        model.addAttribute("shareLinkCount", linkCounts[0]);
        model.addAttribute("redirectLinkCount", linkCounts[1]);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("type", type == null ? "" : type);
        model.addAttribute("noExpiry", noExpiry);
        model.addAttribute("unlimited", unlimited);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortDir", sortDir);

        if (isRedirect) {
            Sort sort = buildRedirectLinkSort(sortBy, sortDir);
            Page<RedirectLink> redirectLinksPage = fileQueryService.getFilteredRedirectLinks(
                    LocalDate.now(), noExpiry, unlimited, trimmedQuery, PageRequest.of(pageNumber, pageSize, sort));
            model.addAttribute("redirectLinksPage", redirectLinksPage);
            model.addAttribute("totalActive", redirectLinksPage.getTotalElements());
        } else {
            Boolean isPaste = switch (type != null ? type : "") {
                case "file" -> false;
                case "paste" -> true;
                default -> null;
            };
            Sort sort = buildShareSort(sortBy, sortDir);
            Page<UploadShareLink> tokensPage = fileQueryService.getFilteredShareTokens(
                    LocalDate.now(), isPaste, noExpiry, unlimited, trimmedQuery, PageRequest.of(pageNumber, pageSize, sort));
            model.addAttribute("tokensPage", tokensPage);
            model.addAttribute("totalActive", tokensPage.getTotalElements());
        }

        return "admin-links";
    }

    /**
     * Builds a {@link Sort} for the share-links tab from the user-supplied field name
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
            case "name" -> Sort.by(dir, "upload.name");
            case "expiry" -> Sort.by(dir, "expirationDate");
            case "downloads" -> Sort.by(dir, "remainingUses");
            default -> Sort.by(dir, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        };
    }

    /**
     * Builds a {@link Sort} for the redirect-links tab. No {@code "name"} option — redirect
     * links have no associated upload to name-sort by; unrecognised values (including
     * {@code "name"} itself) fall back to the {@code "created"} default.
     *
     * @param sortBy  field token: {@code "created"}, {@code "expiry"}, or {@code "downloads"}
     * @param sortDir {@code "asc"} or {@code "desc"}
     * @return the resolved {@link Sort}
     */
    private Sort buildRedirectLinkSort(String sortBy, String sortDir) {
        Sort.Direction dir = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return switch (sortBy) {
            case "expiry" -> Sort.by(dir, "expirationDate");
            case "downloads" -> Sort.by(dir, "remainingUses");
            default -> Sort.by(dir, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        };
    }

    /**
     * Revokes an upload-share token by ID and redirects back to the merged links page.
     *
     * @param id      database ID of the share token to revoke
     * @param request the HTTP request (for history log IP/user-agent metadata)
     * @return redirect to {@code /admin/links}
     */
    @PostMapping("/links/revoke-share/{id}")
    public String revokeShareToken(@PathVariable Long id, HttpServletRequest request) {
        fileLifecycleService.revokeShareToken(id, request);
        return "redirect:/admin/links";
    }

    /**
     * Revokes a redirect (URL-shortener) link by ID and redirects back to the redirect-links tab.
     *
     * @param id      database ID of the redirect link to revoke
     * @param request the HTTP request (for the audit-log admin IP)
     * @return redirect to {@code /admin/links?kind=redirect}
     */
    @PostMapping("/links/revoke-redirect/{id}")
    public String revokeRedirectLink(@PathVariable Long id, HttpServletRequest request) {
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        shortLinkService.revokeRedirectLink(id, info.ipAddress());
        return "redirect:/admin/links?kind=redirect";
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

        LocalDateTime start = parseFilterDate(startDate);
        LocalDateTime end = parseFilterDate(endDate);
        EventType typeFilter = parseFilterEventType(eventType);

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

    /**
     * Streams the activity log as CSV using the same filters as {@link #getActivityPage}.
     * Paged rather than materialised so an unfiltered export doesn't pull the table into heap.
     */
    @GetMapping("/activity/export")
    public ResponseEntity<StreamingResponseBody> exportActivity(@RequestParam(required = false) String startDate,
                                                                @RequestParam(required = false) String endDate,
                                                                @RequestParam(required = false) String eventType,
                                                                @RequestParam(required = false) String ip,
                                                                @RequestParam(required = false) String ua,
                                                                @RequestParam(required = false) String sourceType) {
        LocalDateTime start = parseFilterDate(startDate);
        LocalDateTime end = parseFilterDate(endDate);
        EventType typeFilter = parseFilterEventType(eventType);
        String ipFilter = (ip == null || ip.isBlank()) ? null : ip;
        String uaFilter = (ua == null || ua.isBlank()) ? null : ua;

        String filename = "quickdrop-activity-"
                + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
                + ".csv";

        StreamingResponseBody body = outputStream -> {
            try (java.io.Writer writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                ActivityLogCsv.writeHeader(writer);
                int page = 0;
                Page<ActivityLog> slice;
                do {
                    slice = analyticsService.getFilteredActivity(start, end, typeFilter, ipFilter, uaFilter,
                            sourceType, PageRequest.of(page, EXPORT_PAGE_SIZE));
                    for (ActivityLog entry : slice.getContent()) {
                        ActivityLogCsv.writeRow(writer, entry);
                    }
                    page++;
                } while (slice.hasNext());
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    /** Returns the parsed timestamp, or {@code null} when absent or unparseable. */
    private static LocalDateTime parseFilterDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns the matching event type, or {@code null} when absent or unrecognised. */
    private static EventType parseFilterEventType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EventType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
