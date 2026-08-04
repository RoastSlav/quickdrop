package org.rostislav.quickdrop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.service.ApplicationSettingsService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

/**
 * Tests {@link DelegatingStorageService}'s per-call backend routing with mocked
 * {@link StorageService} beans -- no real cloud backend is ever contacted.
 */
@ExtendWith(MockitoExtension.class)
class DelegatingStorageServiceTest {

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
    @Mock
    private ApplicationSettingsService settings;

    private DelegatingStorageService newService() {
        return new DelegatingStorageService(localStorage, s3Storage, azureStorage, sftpStorage, webDavStorage, settings);
    }

    @Test
    void routesToLocalByDefault() throws IOException {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.LOCAL);
        DelegatingStorageService delegating = newService();
        InputStream stub = new ByteArrayInputStream(new byte[0]);
        when(localStorage.getInputStream("key1")).thenReturn(stub);

        InputStream result = delegating.getInputStream("key1");

        assertSame(stub, result);
        verifyNoInteractions(s3Storage, azureStorage, sftpStorage, webDavStorage);
    }

    @Test
    void routesToS3WhenBackendIsS3() {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.S3);
        DelegatingStorageService delegating = newService();
        when(s3Storage.exists("key1")).thenReturn(true);

        boolean result = delegating.exists("key1");

        assertEquals(true, result);
        verify(s3Storage).exists("key1");
        verifyNoInteractions(localStorage, azureStorage, sftpStorage, webDavStorage);
    }

    @Test
    void routesToAzureWhenBackendIsAzure() {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.AZURE);
        DelegatingStorageService delegating = newService();
        when(azureStorage.delete("key1")).thenReturn(true);

        assertEquals(true, delegating.delete("key1"));
        verify(azureStorage).delete("key1");
        verifyNoInteractions(localStorage, s3Storage, sftpStorage, webDavStorage);
    }

    @Test
    void routesToSftpWhenBackendIsSftp() {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.SFTP);
        DelegatingStorageService delegating = newService();
        when(sftpStorage.listKeySuffix("-decrypted")).thenReturn(List.of("a-decrypted"));

        assertEquals(List.of("a-decrypted"), delegating.listKeySuffix("-decrypted"));
        verify(sftpStorage).listKeySuffix("-decrypted");
        verifyNoInteractions(localStorage, s3Storage, azureStorage, webDavStorage);
    }

    @Test
    void routesToWebDavWhenBackendIsWebDav() {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.WEBDAV);
        DelegatingStorageService delegating = newService();
        when(webDavStorage.getBackend()).thenReturn(StorageBackend.WEBDAV);

        assertEquals(StorageBackend.WEBDAV, delegating.getBackend());
        verify(webDavStorage).getBackend();
        verifyNoInteractions(localStorage, s3Storage, azureStorage, sftpStorage);
    }

    @Test
    void isReachableDelegatesToActiveBackend() {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.S3);
        DelegatingStorageService delegating = newService();
        when(s3Storage.isReachable()).thenReturn(false);

        assertEquals(false, delegating.isReachable());
    }

    @Test
    void routingIsReEvaluatedOnEveryCallSoBackendSwitchTakesEffectImmediately() {
        when(settings.getStorageBackend()).thenReturn(StorageBackend.LOCAL, StorageBackend.S3);
        DelegatingStorageService delegating = newService();
        when(localStorage.exists("k")).thenReturn(true);
        when(s3Storage.exists("k")).thenReturn(false);

        assertEquals(true, delegating.exists("k"));
        assertEquals(false, delegating.exists("k"));
    }
}
