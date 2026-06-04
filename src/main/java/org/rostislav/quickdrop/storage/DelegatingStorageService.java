package org.rostislav.quickdrop.storage;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Primary {@link StorageService} bean that routes all calls to the appropriate backend
 * based on the admin-configured backend. The routing decision is made per-call so that
 * switching backends in the settings page takes effect immediately without a restart.
 */
@Primary
@Service
public class DelegatingStorageService implements StorageService {

    private final LocalStorageService localStorage;
    private final S3StorageService s3Storage;
    private final AzureBlobStorageService azureStorage;
    private final SftpStorageService sftpStorage;
    private final WebDavStorageService webDavStorage;
    private final ApplicationSettingsService settings;

    public DelegatingStorageService(LocalStorageService localStorage,
                                    S3StorageService s3Storage,
                                    AzureBlobStorageService azureStorage,
                                    SftpStorageService sftpStorage,
                                    WebDavStorageService webDavStorage,
                                    ApplicationSettingsService settings) {
        this.localStorage = localStorage;
        this.s3Storage = s3Storage;
        this.azureStorage = azureStorage;
        this.sftpStorage = sftpStorage;
        this.webDavStorage = webDavStorage;
        this.settings = settings;
    }

    private StorageService active() {
        return switch (settings.getStorageBackend()) {
            case S3 -> s3Storage;
            case AZURE -> azureStorage;
            case SFTP -> sftpStorage;
            case WEBDAV -> webDavStorage;
            default -> localStorage;
        };
    }

    @Override
    public InputStream getInputStream(String key) throws IOException {
        return active().getInputStream(key);
    }

    @Override
    public OutputStream getOutputStream(String key) throws IOException {
        return active().getOutputStream(key);
    }

    @Override
    public boolean exists(String key) {
        return active().exists(key);
    }

    @Override
    public boolean delete(String key) {
        return active().delete(key);
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        return active().listKeySuffix(suffix);
    }

    @Override
    public StorageBackend getBackend() {
        return active().getBackend();
    }

    @Override
    public boolean isReachable() {
        return active().isReachable();
    }
}
