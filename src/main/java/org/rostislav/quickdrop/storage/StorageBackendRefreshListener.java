package org.rostislav.quickdrop.storage;

import org.rostislav.quickdrop.service.SettingsChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the cached client for whichever remote storage backend is active whenever
 * settings are saved, so credential/endpoint changes take effect immediately rather than
 * on the next lazy access.
 *
 * <p>LOCAL and SFTP are deliberately not handled here: LOCAL holds no client at all, and
 * {@link SftpStorageService} opens a fresh SSH connection per operation instead of caching
 * one (see its class Javadoc), so neither needs a refresh.
 */
@Component
public class StorageBackendRefreshListener {

    private final S3StorageService s3StorageService;
    private final AzureBlobStorageService azureStorageService;
    private final WebDavStorageService webDavStorageService;

    public StorageBackendRefreshListener(S3StorageService s3StorageService,
                                         AzureBlobStorageService azureStorageService,
                                         WebDavStorageService webDavStorageService) {
        this.s3StorageService = s3StorageService;
        this.azureStorageService = azureStorageService;
        this.webDavStorageService = webDavStorageService;
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        StorageBackend backend = event.settings().getStorageBackend();
        if (backend == StorageBackend.S3) {
            s3StorageService.refreshClient();
        } else if (backend == StorageBackend.AZURE) {
            azureStorageService.refreshClient();
        } else if (backend == StorageBackend.WEBDAV) {
            webDavStorageService.refreshClient();
        }
    }
}
