package org.rostislav.quickdrop.storage;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Primary {@link StorageService} bean that routes all calls to either
 * {@link LocalStorageService} or {@link S3StorageService} based on the admin-configured
 * backend. The routing decision is made per-call so that switching backends in the
 * settings page takes effect immediately without a restart.
 */
@Primary
@Service
public class DelegatingStorageService implements StorageService {

    private final LocalStorageService localStorage;
    private final S3StorageService s3Storage;
    private final ApplicationSettingsService settings;

    public DelegatingStorageService(LocalStorageService localStorage,
                                    S3StorageService s3Storage,
                                    ApplicationSettingsService settings) {
        this.localStorage = localStorage;
        this.s3Storage = s3Storage;
        this.settings = settings;
    }

    private StorageService active() {
        return settings.getStorageBackend() == StorageBackend.S3 ? s3Storage : localStorage;
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
}
