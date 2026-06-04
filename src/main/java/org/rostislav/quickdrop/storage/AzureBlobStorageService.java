package org.rostislav.quickdrop.storage;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link StorageService} backed by Azure Blob Storage.
 * <p>
 * Uses a {@link Supplier} config so admin credential changes take effect without restart.
 * The {@link BlobContainerClient} is rebuilt lazily and on demand via {@link #refreshClient()}.
 */
public class AzureBlobStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(AzureBlobStorageService.class);

    private final Supplier<AzureConfig> configSupplier;
    private volatile BlobContainerClient client;

    public AzureBlobStorageService(Supplier<AzureConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public synchronized void refreshClient() {
        client = buildClient(configSupplier.get());
        logger.info("Azure Blob client refreshed");
    }

    private BlobContainerClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = buildClient(configSupplier.get());
            }
        }
        return client;
    }

    private BlobContainerClient buildClient(AzureConfig cfg) {
        return new BlobContainerClientBuilder()
                .connectionString(cfg.connectionString())
                .containerName(cfg.containerName())
                .buildClient();
    }

    private String blobName(String key) {
        String prefix = configSupplier.get().keyPrefix();
        if (prefix == null || prefix.isBlank()) return key;
        return prefix.endsWith("/") ? prefix + key : prefix + "/" + key;
    }

    @Override
    public InputStream getInputStream(String key) throws IOException {
        try {
            return getClient().getBlobClient(blobName(key)).openInputStream();
        } catch (Exception e) {
            throw new IOException("Failed to open Azure blob: " + key, e);
        }
    }

    @Override
    public OutputStream getOutputStream(String key) throws IOException {
        try {
            return getClient().getBlobClient(blobName(key))
                    .getBlockBlobClient()
                    .getBlobOutputStream(true);
        } catch (Exception e) {
            throw new IOException("Failed to open Azure blob output stream: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return getClient().getBlobClient(blobName(key)).exists();
        } catch (Exception e) {
            logger.warn("Azure exists check failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            getClient().getBlobClient(blobName(key)).deleteIfExists();
            return true;
        } catch (Exception e) {
            logger.error("Failed to delete Azure blob {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        try {
            String prefix = configSupplier.get().keyPrefix();
            List<String> result = new ArrayList<>();
            for (BlobItem item : getClient().listBlobs()) {
                String name = item.getName();
                if (prefix != null && !prefix.isBlank()) {
                    String p = prefix.endsWith("/") ? prefix : prefix + "/";
                    if (name.startsWith(p)) name = name.substring(p.length());
                }
                if (name.endsWith(suffix)) result.add(name);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to list Azure blobs with suffix {}: {}", suffix, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public StorageBackend getBackend() {
        return StorageBackend.AZURE;
    }

    /**
     * Tests the Azure connection by checking if the container exists. Returns null on success, error message on failure.
     */
    public String testConnection() {
        try {
            refreshClient();
            getClient().exists(); // throws if connection string is invalid
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Override
    public boolean isReachable() {
        try {
            return Boolean.TRUE.equals(getClient().exists());
        } catch (Exception e) {
            logger.debug("Azure health probe failed: {}", e.getMessage());
            return false;
        }
    }

    public record AzureConfig(String connectionString, String containerName, String keyPrefix) {
    }
}
