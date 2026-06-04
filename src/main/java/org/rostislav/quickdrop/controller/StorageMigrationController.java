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
        return "admin-storage-migration";
    }

    @PostMapping("/storage-migration/start")
    public String startMigration(@RequestParam StorageBackend source,
                                 @RequestParam StorageBackend dest,
                                 HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
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
     * @param backend the backend to test (S3, AZURE, SFTP, WEBDAV, LOCAL)
     * @return JSON with {@code success} boolean and {@code message} string
     */
    @GetMapping("/api/test-backend")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testBackend(
            @RequestParam StorageBackend backend, HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return ResponseEntity.status(403).build();
        }
        String error = applicationSettingsService.testBackendConnection(backend);
        if (error == null) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Connection successful"));
        }
        return ResponseEntity.ok(Map.of("success", false, "message", error));
    }

}
