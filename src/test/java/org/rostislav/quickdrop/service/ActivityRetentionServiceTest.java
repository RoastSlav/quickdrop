package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.EventCategory;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.rostislav.quickdrop.storage.StorageBackend;
import org.rostislav.quickdrop.storage.StorageService;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRetentionServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private ApplicationSettingsService applicationSettingsService;

    @Autowired
    private AnalyticsService analyticsService;

    private ActivityRetentionService retentionService;
    private CapturingStorage storage;

    /**
     * Builds the service by hand around a capturing storage stub rather than replacing the
     * StorageService bean: the real one is injected elsewhere by its concrete
     * DelegatingStorageService type, so overriding the interface breaks context startup.
     */
    @BeforeEach
    void setUpArchiveCapture() {
        activityLogRepository.deleteAll();
        storage = new CapturingStorage();
        retentionService = new ActivityRetentionService(applicationSettingsService, activityLogRepository,
                storage, analyticsService);
    }

    /** Storage stub that keeps every written object in memory, and can be told to fail. */
    private static final class CapturingStorage implements StorageService {
        private final Map<String, ByteArrayOutputStream> written = new HashMap<>();
        private boolean failWrites;

        @Override
        public OutputStream getOutputStream(String key) throws IOException {
            if (failWrites) {
                throw new IOException("backend unreachable");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            written.put(key, buffer);
            return buffer;
        }

        @Override
        public InputStream getInputStream(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String key) {
            return written.containsKey(key);
        }

        @Override
        public boolean delete(String key) {
            return written.remove(key) != null;
        }

        @Override
        public List<String> listKeySuffix(String suffix) {
            return List.of();
        }

        @Override
        public StorageBackend getBackend() {
            return StorageBackend.LOCAL;
        }
    }

    private void enableRetention(EventCategory category, int days) {
        ApplicationSettingsEntity settings = applicationSettingsService.getApplicationSettings();
        settings.setActivityRetentionEnabled(true);
        // Everything else off, so a test only ever sweeps the category it set up.
        settings.setActivityRetentionFileDays(0);
        settings.setActivityRetentionPasteDays(0);
        settings.setActivityRetentionShareDays(0);
        settings.setActivityRetentionShortlinkDays(0);
        settings.setActivityRetentionAdminDays(0);
        settings.setActivityRetentionSystemDays(0);
        switch (category) {
            case FILE -> settings.setActivityRetentionFileDays(days);
            case PASTE -> settings.setActivityRetentionPasteDays(days);
            case SHARE -> settings.setActivityRetentionShareDays(days);
            case SHORTLINK -> settings.setActivityRetentionShortlinkDays(days);
            case ADMIN -> settings.setActivityRetentionAdminDays(days);
            case SYSTEM -> settings.setActivityRetentionSystemDays(days);
        }
        saveSettings(settings);
    }

    private ActivityLog log(EventType type, LocalDateTime when) {
        ActivityLog entry = new ActivityLog(type, "10.0.0.1", "curl/8.0");
        entry.setEventDate(when);
        return activityLogRepository.save(entry);
    }

    @Test
    void onlyEntriesPastTheCategoryCutoffAreArchivedAndDeleted() {
        enableRetention(EventCategory.ADMIN, 365);
        Long expired = log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400)).getId();
        Long fresh = log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(10)).getId();

        retentionService.runSweep();

        assertFalse(activityLogRepository.existsById(expired), "expired entry should have been purged");
        assertTrue(activityLogRepository.existsById(fresh), "entry inside retention should survive");
    }

    @Test
    void otherCategoriesAreUntouchedByOneCategorysRetention() {
        enableRetention(EventCategory.ADMIN, 365);
        Long oldAdmin = log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400)).getId();
        Long oldDownload = log(EventType.DOWNLOAD, LocalDateTime.now().minusDays(400)).getId();

        retentionService.runSweep();

        assertFalse(activityLogRepository.existsById(oldAdmin));
        assertTrue(activityLogRepository.existsById(oldDownload), "FILE retention is 0 (keep forever)");
    }

    @Test
    void zeroDaysKeepsACategoryForever() {
        enableRetention(EventCategory.ADMIN, 0);
        Long ancient = log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(5000)).getId();

        retentionService.runSweep();

        assertTrue(activityLogRepository.existsById(ancient));
        assertTrue(storage.written.isEmpty(), "nothing should have been archived");
    }

    @Test
    void disabledMasterSwitchSweepsNothing() {
        enableRetention(EventCategory.ADMIN, 365);
        ApplicationSettingsEntity settings = applicationSettingsService.getApplicationSettings();
        settings.setActivityRetentionEnabled(false);
        saveSettings(settings);
        Long expired = log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400)).getId();

        retentionService.runSweep();

        assertTrue(activityLogRepository.existsById(expired));
    }

    /**
     * The whole point of archive-before-purge: if the archive can't be written, the rows must
     * still be there to try again, rather than being deleted with nothing to show for it.
     */
    @Test
    void purgeIsSkippedWhenTheArchiveWriteFails() {
        enableRetention(EventCategory.ADMIN, 365);
        Long expired = log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400)).getId();
        storage.failWrites = true;

        retentionService.runSweep();

        assertTrue(activityLogRepository.existsById(expired), "rows must survive a failed archive");
        assertTrue(activityLogRepository.findAll().stream()
                        .anyMatch(e -> e.getEventType() == EventType.ACTIVITY_RETENTION_FAILED),
                "the failure should be recorded in the activity log");
    }

    @Test
    void archiveContainsAHeaderAndOneRowPerPurgedEntry() {
        enableRetention(EventCategory.ADMIN, 365);
        log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400));
        log(EventType.ADMIN_LOGOUT, LocalDateTime.now().minusDays(401));

        retentionService.runSweep();

        assertEquals(1, storage.written.size(), "one archive object per swept category");
        String csv = storage.written.values().iterator().next().toString(StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();
        assertEquals(3, lines.size(), "header plus two rows");
        assertTrue(lines.get(0).startsWith("event_date,event_type,category"));
        assertTrue(csv.contains("ADMIN_LOGIN"));
        assertTrue(csv.contains("ADMIN_LOGOUT"));
    }

    @Test
    void archiveKeyIsNamespacedByCategory() {
        enableRetention(EventCategory.ADMIN, 365);
        log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400));

        retentionService.runSweep();

        String key = storage.written.keySet().iterator().next();
        assertTrue(key.startsWith(ActivityRetentionService.ARCHIVE_KEY_PREFIX), key);
        assertTrue(key.contains("admin-"), key);
        assertTrue(key.endsWith(".csv"), key);
    }

    /** Exercises the batching loop past a single page. */
    @Test
    void batchesLargerThanOnePageAreFullyArchived() {
        enableRetention(EventCategory.ADMIN, 365);
        for (int i = 0; i < 1200; i++) {
            log(EventType.ADMIN_LOGIN, LocalDateTime.now().minusDays(400));
        }

        retentionService.runSweep();

        String csv = storage.written.values().iterator().next().toString(StandardCharsets.UTF_8);
        assertEquals(1201, csv.lines().count(), "header plus every expired row");
        assertEquals(0, activityLogRepository.findAll().stream()
                .filter(e -> e.getEventType() == EventType.ADMIN_LOGIN).count());
    }

    private void saveSettings(ApplicationSettingsEntity settings) {
        applicationSettingsService.updateApplicationSettings(
                new ApplicationSettingsViewModel(settings), null, null, false);
    }
}
