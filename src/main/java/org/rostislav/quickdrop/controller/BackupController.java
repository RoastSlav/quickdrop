package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.RequesterInfo;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.BackupService;
import org.rostislav.quickdrop.service.RestartTrigger;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.nio.file.Path;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

/**
 * Admin UI for database backups: schedule overview, on-demand backup, and per-backup
 * download/restore/delete.
 *
 * <ul>
 *   <li>{@code GET  /admin/backups}                 — backup list + schedule summary</li>
 *   <li>{@code POST /admin/backups/schedule}         — save the backup schedule</li>
 *   <li>{@code POST /admin/backups/create}           — backup now</li>
 *   <li>{@code POST /admin/backups/upload}           — import an externally-supplied backup</li>
 *   <li>{@code POST /admin/backups/restore}          — restore, then restart</li>
 *   <li>{@code POST /admin/backups/delete}           — delete one backup</li>
 *   <li>{@code GET  /admin/backups/download/{filename}} — download the raw {@code .db} file</li>
 * </ul>
 *
 * <p>Every route is admin-session guarded the same way as every other admin controller in
 * this codebase ({@link SessionService#hasValidAdminSession}, not {@code @PreAuthorize}).
 */
@Controller
@RequestMapping("/admin/backups")
public class BackupController {
    private static final Logger logger = LoggerFactory.getLogger(BackupController.class);


    /** Gives the browser time to receive the restore-success response before the process exits. */
    private static final long RESTART_DELAY_MILLIS = 2000;

    private final BackupService backupService;
    private final SessionService sessionService;
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final RestartTrigger restartTrigger;

    public BackupController(BackupService backupService, SessionService sessionService,
                            ApplicationSettingsService applicationSettingsService,
                            AnalyticsService analyticsService, RestartTrigger restartTrigger) {
        this.backupService = backupService;
        this.sessionService = sessionService;
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.restartTrigger = restartTrigger;
    }

    @GetMapping
    public String backupsPage(Model model, HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        List<BackupService.BackupInfo> backups = backupService.listBackups();
        ApplicationSettingsEntity settings = applicationSettingsService.getApplicationSettings();
        model.addAttribute("backups", backups);
        model.addAttribute("settings", settings);

        // Lead with what an admin actually needs to know: how much is stored, how old the
        // newest one is, and when the next automatic run happens.
        model.addAttribute("backupTotalSize",
                formatFileSize(backups.stream().mapToLong(BackupService.BackupInfo::sizeBytes).sum()));
        model.addAttribute("newestBackupAt",
                backups.stream().map(BackupService.BackupInfo::createdAt).max(Instant::compareTo).orElse(null));
        model.addAttribute("nextScheduledRun", nextScheduledRun(settings));
        return "admin-backups";
    }

    /**
     * Next automatic backup time, or {@code null} when the schedule is off or the cron
     * expression will not fire again. Rendered as plain text on the page.
     */
    private LocalDateTime nextScheduledRun(ApplicationSettingsEntity settings) {
        if (settings == null || !settings.isBackupScheduleEnabled()) {
            return null;
        }
        try {
            CronExpression cron = CronExpression.parse(settings.getBackupCron());
            return cron.next(LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            // An invalid cron is surfaced by the schedule form's own validation; the summary
            // simply shows nothing rather than blowing up the whole page.
            return null;
        }
    }

    @PostMapping("/schedule")
    public String saveSchedule(@RequestParam(defaultValue = "false") boolean backupScheduleEnabled,
                               @RequestParam String backupCron, @RequestParam int maxBackups,
                               HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        if (maxBackups < 1) {
            redirectAttributes.addFlashAttribute("backupError", "Number of backups to keep must be at least 1");
            return "redirect:/admin/backups";
        }
        try {
            CronExpression.parse(backupCron);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("backupError", "Invalid backup cron expression");
            return "redirect:/admin/backups";
        }
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setBackupScheduleEnabled(backupScheduleEnabled);
        vm.setBackupCron(backupCron);
        vm.setMaxBackups(maxBackups);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        analyticsService.logEvent(EventType.ADMIN_SETTINGS_CHANGE, info.ipAddress(), info.userAgent());
        redirectAttributes.addFlashAttribute("scheduleSuccess", true);
        return "redirect:/admin/backups";
    }

    @PostMapping("/create")
    public String createBackup(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        BackupService.BackupResult result = backupService.createBackup();
        if (result.success()) {
            analyticsService.logEvent(EventType.BACKUP_CREATED, info.ipAddress(), info.userAgent());
            redirectAttributes.addFlashAttribute("backupSuccess", result.message());
        } else {
            analyticsService.logEvent(EventType.BACKUP_FAILED, info.ipAddress(), info.userAgent());
            redirectAttributes.addFlashAttribute("backupError", result.message());
        }
        return "redirect:/admin/backups";
    }

    @PostMapping("/upload")
    public String uploadBackup(@RequestParam("file") MultipartFile file,
                               HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        BackupService.BackupResult result = backupService.uploadBackup(file);
        if (result.success()) {
            analyticsService.logEvent(EventType.BACKUP_UPLOADED, info.ipAddress(), info.userAgent());
            redirectAttributes.addFlashAttribute("backupSuccess", result.message());
        } else {
            analyticsService.logEvent(EventType.BACKUP_FAILED, info.ipAddress(), info.userAgent());
            redirectAttributes.addFlashAttribute("backupError", result.message());
        }
        return "redirect:/admin/backups";
    }

    /** On success, renders a dedicated view instead of redirecting — the app is about to exit. */
    @PostMapping("/restore")
    public String restoreBackup(@RequestParam String filename,
                                HttpServletRequest request, Model model, RedirectAttributes redirectAttributes) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
        BackupService.BackupResult result = backupService.restoreBackup(filename);
        if (result.success()) {
            analyticsService.logEvent(EventType.BACKUP_RESTORED, info.ipAddress(), info.userAgent());
            logger.info("Database restored by admin from backup: {}", filename);
            restartTrigger.scheduleRestart(RESTART_DELAY_MILLIS);
            model.addAttribute("filename", filename);
            return "admin-backup-restoring";
        }
        analyticsService.logEvent(EventType.BACKUP_FAILED, info.ipAddress(), info.userAgent());
        logger.warn("Database restore rejected: {}", result.message());
        redirectAttributes.addFlashAttribute("backupError", result.message());
        return "redirect:/admin/backups";
    }

    @PostMapping("/delete")
    public String deleteBackup(@RequestParam String filename,
                               HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!sessionService.hasValidAdminSession(request)) {
            return "redirect:/admin";
        }
        BackupService.BackupResult result = backupService.deleteBackup(filename);
        if (!result.success()) {
            redirectAttributes.addFlashAttribute("backupError", result.message());
        }
        return "redirect:/admin/backups";
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String filename, HttpServletRequest request) {
        if (!sessionService.hasValidAdminSession(request)) {
            return ResponseEntity.status(403).build();
        }
        Path resolved = backupService.resolveForDownload(filename);
        if (resolved == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resolved.getFileName() + "\"")
                .body(new FileSystemResource(resolved));
    }
}
