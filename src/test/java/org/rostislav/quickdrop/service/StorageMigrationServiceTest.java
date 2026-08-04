package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.rostislav.quickdrop.storage.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Plain Mockito unit tests for {@link StorageMigrationService}. All five backend
 * services are concrete classes with non-trivial constructors (AWS/Azure/SFTP/WebDAV
 * clients), but Mockito.mock() never invokes the real constructor, so mocking them
 * directly avoids needing real cloud credentials or Docker/Testcontainers -- per the
 * test-plan instruction to mock cloud SDKs at their client boundary rather than skip
 * this service entirely.
 */
@ExtendWith(MockitoExtension.class)
class StorageMigrationServiceTest {

    @Mock
    private UploadRepository uploadRepository;
    @Mock
    private ShareTokenRepository shareTokenRepository;
    @Mock
    private LocalStorageService localStorage;
    @Mock
    private S3StorageService s3Storage;
    @Mock
    private AzureBlobStorageService azureStorage;
    @Mock
    private SftpStorageService sftpStorage;
    @Mock
    private WebDavStorageService webDavStorage;

    private StorageMigrationService newService() {
        return new StorageMigrationService(uploadRepository, shareTokenRepository,
                localStorage, s3Storage, azureStorage, sftpStorage, webDavStorage);
    }

    private static StorageMigrationService.MigrationState awaitTerminal(StorageMigrationService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        StorageMigrationService.MigrationState state = service.getState();
        while (state.status() == StorageMigrationService.MigrationStatus.RUNNING
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            state = service.getState();
        }
        return state;
    }

    @Test
    void countObjectsSumsUploadUuidsAndShareSidecarKeys() {
        when(uploadRepository.findAllActiveUuids()).thenReturn(List.of("u1", "u2"));
        when(shareTokenRepository.findShareSidecarKeys()).thenReturn(List.of("u1-share-abcde"));

        StorageMigrationService service = newService();

        assertEquals(3, service.countObjects());
    }

    @Test
    void getStateIsIdleBeforeAnyMigrationStarts() {
        StorageMigrationService service = newService();
        StorageMigrationService.MigrationState state = service.getState();
        assertEquals(StorageMigrationService.MigrationStatus.IDLE, state.status());
        assertEquals(0, state.total());
        assertEquals(0, state.migrated());
        assertEquals(0, state.failed());
        assertTrue(state.errors().isEmpty());
    }

    @Test
    @Timeout(10)
    void startRejectsConcurrentMigration() throws Exception {
        CountDownLatch releaseLatch = new CountDownLatch(1);
        // Block inside buildKeyList() so the background task stays RUNNING until we
        // explicitly release it -- makes the "already running" race deterministic.
        when(uploadRepository.findAllActiveUuids()).thenAnswer(inv -> {
            releaseLatch.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        when(shareTokenRepository.findShareSidecarKeys()).thenReturn(List.of());

        StorageMigrationService service = newService();
        service.start(new StorageMigrationService.MigrationDirection(StorageBackend.LOCAL, StorageBackend.S3));

        assertThrows(IllegalStateException.class, () ->
                service.start(new StorageMigrationService.MigrationDirection(StorageBackend.LOCAL, StorageBackend.S3)));

        releaseLatch.countDown();
        awaitTerminal(service);
    }

    @Test
    @Timeout(10)
    void successfulMigrationCopiesEveryKeyAndReportsCompleted() throws Exception {
        when(uploadRepository.findAllActiveUuids()).thenReturn(List.of("u1", "u2"));
        when(shareTokenRepository.findShareSidecarKeys()).thenReturn(List.of());
        when(localStorage.exists("u1")).thenReturn(true);
        when(localStorage.exists("u2")).thenReturn(true);
        when(localStorage.getInputStream(anyString())).thenAnswer(inv -> new ByteArrayInputStream("data".getBytes()));
        when(s3Storage.getOutputStream(anyString())).thenAnswer(inv -> new ByteArrayOutputStream());

        StorageMigrationService service = newService();
        service.start(new StorageMigrationService.MigrationDirection(StorageBackend.LOCAL, StorageBackend.S3));

        StorageMigrationService.MigrationState finalState = awaitTerminal(service);

        assertEquals(StorageMigrationService.MigrationStatus.COMPLETED, finalState.status());
        assertEquals(2, finalState.total());
        assertEquals(2, finalState.migrated());
        assertEquals(0, finalState.failed());
        verify(localStorage).getInputStream("u1");
        verify(localStorage).getInputStream("u2");
    }

    @Test
    @Timeout(10)
    void keyMissingOnSourceIsSkippedNotFailed() throws Exception {
        when(uploadRepository.findAllActiveUuids()).thenReturn(List.of("ghost-uuid"));
        when(shareTokenRepository.findShareSidecarKeys()).thenReturn(List.of());
        when(localStorage.exists("ghost-uuid")).thenReturn(false);

        StorageMigrationService service = newService();
        service.start(new StorageMigrationService.MigrationDirection(StorageBackend.LOCAL, StorageBackend.S3));

        StorageMigrationService.MigrationState finalState = awaitTerminal(service);

        assertEquals(StorageMigrationService.MigrationStatus.COMPLETED, finalState.status());
        assertEquals(0, finalState.total(), "skipped keys should be decremented from the total, not counted as failures");
        assertEquals(0, finalState.migrated());
        assertEquals(0, finalState.failed());
        verify(localStorage, never()).getInputStream(anyString());
    }

    @Test
    @Timeout(10)
    void copyFailureIsRecordedAndStatusReflectsPartialFailure() throws Exception {
        when(uploadRepository.findAllActiveUuids()).thenReturn(List.of("bad-uuid"));
        when(shareTokenRepository.findShareSidecarKeys()).thenReturn(List.of());
        when(localStorage.exists("bad-uuid")).thenReturn(true);
        when(localStorage.getInputStream("bad-uuid")).thenThrow(new IOException("disk read error"));

        StorageMigrationService service = newService();
        service.start(new StorageMigrationService.MigrationDirection(StorageBackend.LOCAL, StorageBackend.S3));

        StorageMigrationService.MigrationState finalState = awaitTerminal(service);

        assertEquals(StorageMigrationService.MigrationStatus.COMPLETED_WITH_ERRORS, finalState.status());
        assertEquals(1, finalState.total());
        assertEquals(0, finalState.migrated());
        assertEquals(1, finalState.failed());
        assertFalse(finalState.errors().isEmpty());
        assertTrue(finalState.errors().get(0).contains("bad-uuid"));
    }
}
