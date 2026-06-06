package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.service.StorageMigrationService;
import org.rostislav.quickdrop.service.StorageMigrationService.MigrationDirection;
import org.rostislav.quickdrop.storage.StorageBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.Map;

/**
 * Admin UI and JSON API for the storage migration tool.
 *
 * <ul>
 *   <li>{@code GET  /admin/storage-migration}          — migration page</li>
 *   <li>{@code POST /admin/storage-migration/start}    — start a background migration</li>
 *   <li>{@code GET  /admin/api/migration-status}       — JSON progress for polling</li>
 *   <li>{@code GET  /admin/api/migration-preflight}    — preflight object count</li>
 *   <li>{@code GET  /admin/api/test-backend}           — test any backend connectivity</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin")
public class StorageMigrationController {
    private static final Logger logger = LoggerFactory.getLogger(StorageMigrationController.class);

    private final StorageMigrationService migrationService;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;

    public StorageMigrationController(StorageMigrationService migrationService,
                                      ApplicationSettingsService applicationSettingsService,
                                      SessionService sessionService) {
        this.migrationService = migrationService;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
    }

    @GetMapping("/storage-migration")
    public String migrationPage(Model model, HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        model.addAttribute("state", migrationService.getState());
        model.addAttribute("currentBackend", applicationSettingsService.getStorageBackend());
        java.util.Map<String, Boolean> configured = new java.util.LinkedHashMap<>();
        for (StorageBackend b : StorageBackend.values()) {
            configured.put(b.name(), applicationSettingsService.isBackendConfigured(b));
        }
        model.addAttribute("backendConfigured", configured);
        return "admin-storage-migration";
    }

    @PostMapping("/storage-migration/start")
    public String startMigration(@RequestParam StorageBackend source,
                                 @RequestParam StorageBackend dest,
                                 HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        if (!applicationSettingsService.isBackendConfigured(source) || !applicationSettingsService.isBackendConfigured(dest)) {
            logger.warn("Migration rejected: backend not fully configured ({} → {})", source, dest);
            return "redirect:/admin/storage-migration";
        }
        try {
            MigrationDirection dir = new MigrationDirection(source, dest);
            migrationService.start(dir);
            logger.info("Storage migration started by admin: {} → {}", source, dest);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid migration direction: {} → {}", source, dest);
        } catch (IllegalStateException e) {
            logger.warn("Migration start rejected: {}", e.getMessage());
        }
        return "redirect:/admin/storage-migration";
    }

    @GetMapping("/api/migration-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> migrationStatus(HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return ResponseEntity.status(403).build();
        }
        StorageMigrationService.MigrationState state = migrationService.getState();
        String directionStr = "";
        if (state.direction() != null) {
            directionStr = state.direction().source().name() + "_TO_" + state.direction().dest().name();
        }
        return ResponseEntity.ok(Map.of(
                "status", state.status().name(),
                "direction", directionStr,
                "total", state.total(),
                "migrated", state.migrated(),
                "failed", state.failed(),
                "errors", state.errors()
        ));
    }

    /**
     * Returns a lightweight pre-flight summary for the migration UI.
     *
     * <p>Queries the database for the number of objects that would be copied, so the
     * admin can confirm the scope before starting. No storage I/O is performed.
     *
     * @return {@code {"count": N}} where N is the number of files that would be migrated
     */
    @GetMapping("/api/migration-preflight")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> migrationPreflight(HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(Map.of("count", migrationService.countObjects()));
    }

    /**
     * Generic backend connectivity test endpoint.
     *
     * <p>Before delegating to {@link ApplicationSettingsService#testBackendConnection(StorageBackend)},
     * the configured endpoint/host for the requested backend is validated with
     * {@link #isSafeEndpoint(String)} to prevent SSRF probing of internal network addresses.
     *
     * @param backend the backend to test (S3, AZURE, SFTP, WEBDAV, LOCAL)
     * @return JSON with {@code success} boolean and {@code message} or {@code error} string
     */
    @GetMapping("/api/test-backend")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testBackend(
            @RequestParam StorageBackend backend, HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return ResponseEntity.status(403).build();
        }

        // Validate the configured endpoint/host before making any live network connection.
        String endpointToCheck = switch (backend) {
            case S3 -> applicationSettingsService.getS3Endpoint();
            case WEBDAV -> applicationSettingsService.getWebDavUrl();
            case SFTP -> applicationSettingsService.getSftpHost();
            default -> null; // LOCAL, AZURE — no user-supplied host to validate here
        };

        if (endpointToCheck != null && endpointToCheck.isBlank()) {
            // Backend requires a host/URL but none is configured yet — fail fast with a clear message.
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "No host or URL configured for this backend."));
        }
        if (endpointToCheck != null && !isSafeEndpoint(endpointToCheck)) {
            logger.warn("Backend test blocked for {}: endpoint '{}' resolved to an internal/loopback address", backend, endpointToCheck);
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Invalid or internal endpoint address."));
        }

        String error = applicationSettingsService.testBackendConnection(backend);
        if (error == null) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Connection successful"));
        }
        return ResponseEntity.ok(Map.of("success", false, "message", error));
    }

    /**
     * Returns {@code true} when {@code url} (or bare hostname) does NOT resolve to a
     * loopback or RFC-1918 private address, and — for URL-form inputs — uses HTTPS.
     *
     * <p>Rules:
     * <ul>
     *   <li>For URL-form inputs (starts with {@code http://} or {@code https://}):
     *       scheme must be {@code https}; host is extracted then resolved.</li>
     *   <li>For bare hostnames (SFTP host field): resolved directly.</li>
     *   <li>Rejected address ranges: loopback (127.x, ::1), link-local (169.254.x),
     *       and RFC-1918 private ranges (10.x, 172.16-31.x, 192.168.x).</li>
     *   <li>{@code localhost} is always rejected.</li>
     * </ul>
     *
     * @param url a URL string or bare hostname to validate
     * @return {@code true} if the endpoint is considered safe to connect to
     */
    private boolean isSafeEndpoint(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                java.net.URI uri = new java.net.URI(url);
                // Require HTTPS for URL-form endpoints
                if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
                host = uri.getHost();
            } else {
                // Bare hostname (e.g. SFTP host)
                host = url;
            }

            if (host == null || host.isBlank()) return false;

            // Reject obvious literal hostnames before DNS resolution
            String lowerHost = host.toLowerCase();
            if (lowerHost.equals("localhost") || lowerHost.startsWith("localhost.")) return false;

            InetAddress addr = InetAddress.getByName(host);
            byte[] b = addr.getAddress();

            if (addr.isLoopbackAddress()) return false;
            if (addr.isLinkLocalAddress()) return false;

            if (b.length == 4) {
                int b0 = b[0] & 0xFF;
                int b1 = b[1] & 0xFF;
                // 10.x.x.x
                if (b0 == 10) return false;
                // 172.16.x.x – 172.31.x.x
                if (b0 == 172 && b1 >= 16 && b1 <= 31) return false;
                // 192.168.x.x
                if (b0 == 192 && b1 == 168) return false;
            }

            return true;
        } catch (Exception e) {
            // Resolution failure or malformed URL — treat as unsafe
            return false;
        }
    }

}
