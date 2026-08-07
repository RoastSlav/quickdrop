package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.service.BackupService;
import org.rostislav.quickdrop.service.RestartTrigger;
import org.rostislav.quickdrop.service.TestBackupServiceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-slice tests for {@code /admin/backups}, run against a real Spring context via
 * {@link org.springframework.test.web.servlet.MockMvc} with {@link TestBackupServiceConfig}
 * substituted in (see its javadoc) so nothing here touches the repo's real {@code db-backups/}
 * directory or exits the test JVM.
 */
@ContextConfiguration(classes = TestBackupServiceConfig.class)
class BackupControllerTest extends ControllerTestSupport {

    @Autowired
    private BackupService backupService;
    @Autowired
    private RestartTrigger restartTrigger;
    @Autowired
    private ActivityLogRepository activityLogRepository;

    // -- GET /admin/backups ---------------------------------------------------

    @Test
    void backupsPage_withAdminSession_returns200() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/backups").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-backups"))
                .andExpect(model().attributeExists("backups", "settings"));
    }

    @Test
    void backupsPage_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/backups"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    // -- POST /admin/backups/schedule --------------------------------------------

    @Test
    void saveSchedule_validValues_persistsAndRedirectsWithSuccessFlash() throws Exception {
        MockHttpSession session = adminSession();

        try {
            mockMvc.perform(post("/admin/backups/schedule").session(session).with(csrf())
                            .param("backupScheduleEnabled", "true")
                            .param("backupCron", "0 30 4 * * *")
                            .param("maxBackups", "3"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/backups"))
                    .andExpect(flash().attributeExists("scheduleSuccess"));

            var settings = applicationSettingsService.getApplicationSettings();
            assertTrue(settings.isBackupScheduleEnabled());
            assertEquals("0 30 4 * * *", settings.getBackupCron());
            assertEquals(3, settings.getMaxBackups());
        } finally {
            updateSettings(s -> {
                s.setBackupScheduleEnabled(false);
                s.setBackupCron("0 0 4 * * *");
                s.setMaxBackups(7);
            });
        }
    }

    @Test
    void saveSchedule_invalidCron_rejectedWithErrorFlashAndDoesNotPersist() throws Exception {
        MockHttpSession session = adminSession();
        String before = applicationSettingsService.getApplicationSettings().getBackupCron();

        mockMvc.perform(post("/admin/backups/schedule").session(session).with(csrf())
                        .param("backupScheduleEnabled", "false")
                        .param("backupCron", "not a cron")
                        .param("maxBackups", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("backupError"));

        assertEquals(before, applicationSettingsService.getApplicationSettings().getBackupCron());
    }

    @Test
    void saveSchedule_zeroMaxBackups_rejectedWithErrorFlashAndDoesNotPersist() throws Exception {
        MockHttpSession session = adminSession();
        int before = applicationSettingsService.getApplicationSettings().getMaxBackups();

        mockMvc.perform(post("/admin/backups/schedule").session(session).with(csrf())
                        .param("backupScheduleEnabled", "false")
                        .param("backupCron", "0 0 4 * * *")
                        .param("maxBackups", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("backupError"));

        assertEquals(before, applicationSettingsService.getApplicationSettings().getMaxBackups());
    }

    @Test
    void saveSchedule_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/backups/schedule").with(csrf())
                        .param("backupCron", "0 0 4 * * *").param("maxBackups", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void saveSchedule_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/backups/schedule").session(session)
                        .param("backupCron", "0 0 4 * * *").param("maxBackups", "7"))
                .andExpect(status().isForbidden());
    }

    // -- POST /admin/backups/create --------------------------------------------

    @Test
    void createBackup_withAdminSession_createsFileAndRedirectsWithSuccessFlash() throws Exception {
        MockHttpSession session = adminSession();
        long before = backupService.listBackups().size();

        mockMvc.perform(post("/admin/backups/create").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attributeExists("backupSuccess"));

        assertEquals(before + 1, backupService.listBackups().size());
        assertTrue(activityLogRepository.countByEventType(EventType.BACKUP_CREATED) > 0);
    }

    @Test
    void createBackup_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/backups/create").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void createBackup_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/backups/create").session(session))
                .andExpect(status().isForbidden());
    }

    // -- POST /admin/backups/upload ----------------------------------------------

    @Test
    void uploadBackup_validFile_isAcceptedAndRedirectsWithSuccessFlash() throws Exception {
        MockHttpSession session = adminSession();
        byte[] validSqliteBytes = Files.readAllBytes(backupService.resolveForDownload(backupService.createBackup().message()));
        MockMultipartFile file = new MockMultipartFile("file", "external.db", "application/octet-stream", validSqliteBytes);
        long before = backupService.listBackups().size();

        mockMvc.perform(multipart("/admin/backups/upload").file(file).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attributeExists("backupSuccess"));

        assertEquals(before + 1, backupService.listBackups().size());
        assertTrue(activityLogRepository.countByEventType(EventType.BACKUP_UPLOADED) > 0);
    }

    @Test
    void uploadBackup_corruptFile_isRejectedWithErrorFlash() throws Exception {
        MockHttpSession session = adminSession();
        MockMultipartFile file = new MockMultipartFile("file", "external.db", "application/octet-stream",
                "not a real sqlite database".getBytes());
        long before = backupService.listBackups().size();

        mockMvc.perform(multipart("/admin/backups/upload").file(file).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attributeExists("backupError"));

        assertEquals(before, backupService.listBackups().size(), "a rejected upload must not be added to the backup list");
    }

    @Test
    void uploadBackup_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        MockMultipartFile file = new MockMultipartFile("file", "external.db", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart("/admin/backups/upload").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void uploadBackup_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        MockMultipartFile file = new MockMultipartFile("file", "external.db", "application/octet-stream", "x".getBytes());

        mockMvc.perform(multipart("/admin/backups/upload").file(file).session(session))
                .andExpect(status().isForbidden());
    }

    // -- POST /admin/backups/restore -------------------------------------------

    @Test
    void restoreBackup_happyPath_rendersRestoringViewAndTriggersRestart() throws Exception {
        MockHttpSession session = adminSession();
        String filename = backupService.createBackup().message();
        int callsBefore = ((TestBackupServiceConfig.RecordingRestartTrigger) restartTrigger).callCount.get();

        mockMvc.perform(post("/admin/backups/restore").session(session).with(csrf())
                        .param("filename", filename))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-backup-restoring"))
                .andExpect(model().attribute("filename", filename));

        assertEquals(callsBefore + 1, ((TestBackupServiceConfig.RecordingRestartTrigger) restartTrigger).callCount.get(),
                "a successful restore must schedule exactly one restart");
        assertTrue(activityLogRepository.countByEventType(EventType.BACKUP_RESTORED) > 0);
    }

    @Test
    void restoreBackup_nonExistentFilename_redirectsWithErrorFlashAndDoesNotTriggerRestart() throws Exception {
        MockHttpSession session = adminSession();
        int callsBefore = ((TestBackupServiceConfig.RecordingRestartTrigger) restartTrigger).callCount.get();

        mockMvc.perform(post("/admin/backups/restore").session(session).with(csrf())
                        .param("filename", "quickdrop-2020-01-01T00-00-00.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backups"))
                .andExpect(flash().attributeExists("backupError"));

        assertEquals(callsBefore, ((TestBackupServiceConfig.RecordingRestartTrigger) restartTrigger).callCount.get(),
                "a rejected restore must never schedule a restart");
    }

    @Test
    void restoreBackup_pathTraversalFilename_isRejected() throws Exception {
        MockHttpSession session = adminSession();

        mockMvc.perform(post("/admin/backups/restore").session(session).with(csrf())
                        .param("filename", "../evil.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("backupError"));
    }

    @Test
    void restoreBackup_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/backups/restore").with(csrf()).param("filename", "x.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void restoreBackup_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/backups/restore").session(session).param("filename", "x.db"))
                .andExpect(status().isForbidden());
    }

    // -- POST /admin/backups/delete ---------------------------------------------

    @Test
    void deleteBackup_withAdminSession_removesFileAndRedirects() throws Exception {
        MockHttpSession session = adminSession();
        String filename = backupService.createBackup().message();

        mockMvc.perform(post("/admin/backups/delete").session(session).with(csrf())
                        .param("filename", filename))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/backups"));

        assertTrue(backupService.listBackups().stream().noneMatch(b -> b.filename().equals(filename)));
    }

    @Test
    void deleteBackup_pathTraversalFilename_isRejectedWithErrorFlash() throws Exception {
        MockHttpSession session = adminSession();

        mockMvc.perform(post("/admin/backups/delete").session(session).with(csrf())
                        .param("filename", "../evil.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("backupError"));
    }

    @Test
    void deleteBackup_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(post("/admin/backups/delete").with(csrf()).param("filename", "x.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void deleteBackup_withoutCsrf_isForbidden() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(post("/admin/backups/delete").session(session).param("filename", "x.db"))
                .andExpect(status().isForbidden());
    }

    // -- GET /admin/backups/download/{filename} ----------------------------------

    @Test
    void downloadBackup_withAdminSession_streamsFileWithAttachmentHeader() throws Exception {
        MockHttpSession session = adminSession();
        String filename = backupService.createBackup().message();

        mockMvc.perform(get("/admin/backups/download/{filename}", filename).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + filename + "\""));
    }

    @Test
    void downloadBackup_missingFile_returns404() throws Exception {
        MockHttpSession session = adminSession();

        mockMvc.perform(get("/admin/backups/download/{filename}", "quickdrop-2020-01-01T00-00-00.db").session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadBackup_unauthenticated_isBlockedByAdminInterceptor() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/backups/download/{filename}", "x.db"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }
}
