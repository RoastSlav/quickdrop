package org.rostislav.quickdrop.storage;

import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link StorageService} backed by WebDAV (e.g. Nextcloud, ownCloud, generic WebDAV server).
 * <p>
 * Uses Sardine as the HTTP client. A new Sardine instance is created per config refresh
 * and reused across calls.
 */
public class WebDavStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(WebDavStorageService.class);

    private final Supplier<WebDavConfig> configSupplier;
    private volatile Sardine sardine;

    public WebDavStorageService(Supplier<WebDavConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public synchronized void refreshClient() {
        sardine = SardineFactory.begin(configSupplier.get().username(), configSupplier.get().password());
        logger.info("WebDAV client refreshed");
    }

    private Sardine getSardine() {
        if (sardine == null) {
            synchronized (this) {
                if (sardine == null) refreshClient();
            }
        }
        return sardine;
    }

    private String fullUrl(String key) {
        WebDavConfig cfg = configSupplier.get();
        String base = cfg.url();
        if (base == null || base.isBlank()) base = "/";
        if (!base.endsWith("/")) base += "/";
        String prefix = cfg.keyPrefix();
        if (prefix != null && !prefix.isBlank()) {
            if (!prefix.endsWith("/")) prefix += "/";
            return base + prefix + key;
        }
        return base + key;
    }

    @Override
    public InputStream getInputStream(String key) throws IOException {
        try {
            return getSardine().get(fullUrl(key));
        } catch (Exception e) {
            throw new IOException("WebDAV GET failed for " + key, e);
        }
    }

    @Override
    public OutputStream getOutputStream(String key) throws IOException {
        // Buffer to memory then PUT on close — WebDAV PUT requires the full content.
        // Use a temp file to handle large files safely.
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("qd-webdav-", ".tmp");
            OutputStream base = java.nio.file.Files.newOutputStream(tmp);
            return new FilterOutputStream(base) {
                @Override
                public void close() throws IOException {
                    super.close();
                    try {
                        byte[] data = java.nio.file.Files.readAllBytes(tmp);
                        getSardine().put(fullUrl(key), data);
                    } catch (Exception e) {
                        throw new IOException("WebDAV PUT failed for " + key, e);
                    } finally {
                        java.nio.file.Files.deleteIfExists(tmp);
                    }
                }
            };
        } catch (IOException e) {
            throw new IOException("WebDAV write setup failed for " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return getSardine().exists(fullUrl(key));
        } catch (Exception e) {
            logger.warn("WebDAV exists check failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            getSardine().delete(fullUrl(key));
            return true;
        } catch (Exception e) {
            logger.error("WebDAV delete failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        try {
            WebDavConfig cfg = configSupplier.get();
            String base = cfg.url();
            if (!base.endsWith("/")) base += "/";
            String prefix = cfg.keyPrefix();
            String listUrl = (prefix != null && !prefix.isBlank()) ? base + (prefix.endsWith("/") ? prefix : prefix + "/") : base;
            List<String> result = new ArrayList<>();
            for (DavResource r : getSardine().list(listUrl)) {
                if (!r.isDirectory()) {
                    String name = r.getName();
                    if (name.endsWith(suffix)) result.add(name);
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("WebDAV list failed with suffix {}: {}", suffix, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public StorageBackend getBackend() {
        return StorageBackend.WEBDAV;
    }

    /**
     * Returns null on success, error message on failure.
     */
    public String testConnection() {
        try {
            refreshClient();
            WebDavConfig cfg = configSupplier.get();
            String base = cfg.url();
            if (base == null || base.isBlank()) return "WebDAV URL is not configured";
            getSardine().exists(base);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Override
    public boolean isReachable() {
        try {
            String url = configSupplier.get().url();
            return url != null && !url.isBlank() && getSardine().exists(url);
        } catch (Exception e) {
            logger.debug("WebDAV health probe failed: {}", e.getMessage());
            return false;
        }
    }

    public record WebDavConfig(String url, String username, String password, String keyPrefix) {
    }
}
