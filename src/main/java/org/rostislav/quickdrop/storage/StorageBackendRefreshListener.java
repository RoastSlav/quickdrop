package org.rostislav.quickdrop.storage;

import org.rostislav.quickdrop.service.SettingsChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the cached client for the active remote storage backend whenever settings are
 * saved, so credential/endpoint changes take effect immediately. LOCAL and SFTP aren't
 * handled here: LOCAL holds no client, and {@link SftpStorageService} opens a fresh
 * connection per operation instead of caching one.
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
