package org.rostislav.quickdrop.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * {@link StorageService} implementation backed by the local filesystem.
 *
 * <p>The root directory is supplied by {@link LocalStorageService#LocalStorageService(String)}
 * and is re-read from the delegating wrapper on each call so that admin changes to the
 * storage path take effect immediately.
 */
public class LocalStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(LocalStorageService.class);

    private final java.util.function.Supplier<String> rootSupplier;

    public LocalStorageService(java.util.function.Supplier<String> rootSupplier) {
        this.rootSupplier = rootSupplier;
    }

    private Path resolve(String key) {
        return Path.of(rootSupplier.get(), key);
    }

    @Override
    public InputStream getInputStream(String key) throws IOException {
        return new FileInputStream(resolve(key).toFile());
    }

    @Override
    public OutputStream getOutputStream(String key) throws IOException {
        Path path = resolve(key);
        Files.createDirectories(path.getParent());
        return new FileOutputStream(path.toFile(), false);
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public boolean delete(String key) {
        Path path = resolve(key);
        try {
            boolean existed = Files.deleteIfExists(path);
            if (!existed) {
                logger.warn("File already absent from filesystem: {}", path);
            } else {
                logger.info("File deleted from local storage: {}", path);
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete from local storage: {}", path, e);
            return false;
        }
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        Path root = Path.of(rootSupplier.get());
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        try (var stream = Files.list(root)) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .toList();
        } catch (IOException e) {
            logger.error("Failed to list storage directory: {}", root, e);
            return Collections.emptyList();
        }
    }

    @Override
    public StorageBackend getBackend() {
        return StorageBackend.LOCAL;
    }
}
