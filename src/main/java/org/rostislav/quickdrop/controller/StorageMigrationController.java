package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.service.StorageMigrationService;
import org.rostislav.quickdrop.service.StorageMigrationService.MigrationDirection;
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
 *   <li>{@code GET  /admin/storage-migration}        — migration page</li>
 *   <li>{@code POST /admin/storage-migration/start}  — start a background migration</li>
 *   <li>{@code GET  /admin/api/migration-status}     — JSON progress for polling</li>
 *   <li>{@code GET  /admin/api/test-s3}              — test S3 connectivity</li>
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
        model.addAttribute("s3Configured", isS3Configured());
        return "admin-storage-migration";
    }

    @PostMapping("/storage-migration/start")
    public String startMigration(@RequestParam("direction") String direction,
                                 HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        try {
            MigrationDirection dir = MigrationDirection.valueOf(direction);
            migrationService.start(dir);
            logger.info("Storage migration started by admin: {}", dir);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid migration direction: {}", direction);
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
        return ResponseEntity.ok(Map.of(
                "status", state.status().name(),
                "direction", state.direction() != null ? state.direction().name() : "",
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

    @GetMapping("/api/test-s3")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testS3(HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return ResponseEntity.status(403).build();
        }
        String error = applicationSettingsService.testS3Connection();
        if (error == null) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Connection successful"));
        }
        return ResponseEntity.ok(Map.of("success", false, "message", error));
    }

    private boolean isS3Configured() {
        String bucket = applicationSettingsService.getS3Bucket();
        String key = applicationSettingsService.getS3AccessKey();
        String secret = applicationSettingsService.getS3SecretKey();
        return bucket != null && !bucket.isBlank()
                && key != null && !key.isBlank()
                && secret != null && !secret.isBlank();
    }
}
