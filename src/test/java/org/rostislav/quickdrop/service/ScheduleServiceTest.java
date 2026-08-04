package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ScheduleService}'s cleanup jobs (invoked directly rather than waiting
 * on their {@code @Scheduled} cron triggers) against the real Spring context.
 */
class ScheduleServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private UploadRepository uploadRepository;
    @Autowired
    private ShareTokenRepository shareTokenRepository;
    @Autowired
    private StorageService storageService;

    private StoredFile persistFile(LocalDate uploadDate, boolean keepIndefinitely) {
        StoredFile file = new StoredFile();
        file.uuid = UUID.randomUUID().toString();
        file.name = "f-" + file.uuid + ".txt";
        file.size = 5;
        file.keepIndefinitely = keepIndefinitely;
        StoredFile saved = fileRepository.save(file);
        // @PrePersist stamps uploadDate = today on insert; overwrite it directly for the test.
        saved.uploadDate = uploadDate;
        return fileRepository.save(saved);
    }

    // -------------------------------------------------------------------------
    // deleteOldFiles
    // -------------------------------------------------------------------------

    @Test
    void deleteOldFilesRemovesExpiredNonPinnedFiles() {
        StoredFile expired = persistFile(LocalDate.now().minusDays(40), false);

        scheduleService.deleteOldFiles(30);

        assertTrue(uploadRepository.findByUUID(expired.uuid).orElseThrow().deleted);
    }

    @Test
    void deleteOldFilesLeavesKeepIndefinitelyFilesAlone() {
        StoredFile pinned = persistFile(LocalDate.now().minusDays(40), true);

        scheduleService.deleteOldFiles(30);

        assertFalse(uploadRepository.findByUUID(pinned.uuid).orElseThrow().deleted,
                "keepIndefinitely files must be immune to the age-based cleanup");
    }

    @Test
    void deleteOldFilesLeavesRecentFilesAlone() {
        StoredFile recent = persistFile(LocalDate.now(), false);

        scheduleService.deleteOldFiles(30);

        assertFalse(uploadRepository.findByUUID(recent.uuid).orElseThrow().deleted);
    }

    @Test
    void deleteOldFilesIsANoOpWhenNothingIsEligible() {
        persistFile(LocalDate.now(), false);
        assertDoesNotThrow(() -> scheduleService.deleteOldFiles(30));
    }

    // -------------------------------------------------------------------------
    // cleanDatabaseFromDeletedFiles (orphan-row cleanup)
    // -------------------------------------------------------------------------

    @Test
    void cleanDatabaseFromDeletedFilesSoftDeletesOrphanedRows() throws Exception {
        // Row exists in the DB but has no backing blob on disk -- an orphan.
        StoredFile orphan = persistFile(LocalDate.now(), false);
        assertFalse(storageService.exists(orphan.uuid), "precondition: no physical file was written for this uuid");

        // A second file that DOES have a physical blob must be left alone.
        StoredFile healthy = persistFile(LocalDate.now(), false);
        try (var out = storageService.getOutputStream(healthy.uuid)) {
            out.write("real content".getBytes());
        }

        scheduleService.cleanDatabaseFromDeletedFiles();

        assertTrue(uploadRepository.findByUUID(orphan.uuid).orElseThrow().deleted,
                "a DB row whose physical file is missing must be soft-deleted by the orphan scan");
        assertFalse(uploadRepository.findByUUID(healthy.uuid).orElseThrow().deleted,
                "a file with a real backing blob must not be touched");
    }

    // -------------------------------------------------------------------------
    // cleanShareTokens
    // -------------------------------------------------------------------------

    @Test
    void cleanShareTokensRemovesExpiredAndExhaustedTokensButKeepsValidOnes() {
        StoredFile file = persistFile(LocalDate.now(), false);

        ShareTokenEntity expired = new ShareTokenEntity("exprd", file, LocalDate.now().minusDays(1), null);
        ShareTokenEntity exhausted = new ShareTokenEntity("exhst", file, null, 0);
        ShareTokenEntity valid = new ShareTokenEntity("valid", file, LocalDate.now().plusDays(5), 3);
        ShareTokenEntity unlimited = new ShareTokenEntity("unlim", file, null, null);
        shareTokenRepository.save(expired);
        shareTokenRepository.save(exhausted);
        shareTokenRepository.save(valid);
        shareTokenRepository.save(unlimited);

        scheduleService.cleanShareTokens();

        assertTrue(shareTokenRepository.findByShareToken("exprd").isEmpty());
        assertTrue(shareTokenRepository.findByShareToken("exhst").isEmpty());
        assertTrue(shareTokenRepository.findByShareToken("valid").isPresent());
        assertTrue(shareTokenRepository.findByShareToken("unlim").isPresent());
    }

    @Test
    void cleanShareTokensIsANoOpWhenNothingIsExpired() {
        StoredFile file = persistFile(LocalDate.now(), false);
        shareTokenRepository.save(new ShareTokenEntity("stays", file, LocalDate.now().plusDays(1), null));

        assertDoesNotThrow(() -> scheduleService.cleanShareTokens());

        assertTrue(shareTokenRepository.findByShareToken("stays").isPresent());
    }
}
