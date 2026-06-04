package org.rostislav.quickdrop.storage;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.function.Supplier;

/**
 * {@link StorageService} backed by SFTP (SSH File Transfer Protocol).
 *
 * <p>Uses a {@link Supplier} config. A JSch {@link Session} is kept alive and
 * reconnected on demand. Each individual I/O operation opens and closes its own
 * {@link ChannelSftp}.
 *
 * <p>Writes buffer to a local temp file then upload on {@link OutputStream#close()}
 * to avoid holding an SSH channel open for the full transfer duration.
 */
public class SftpStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(SftpStorageService.class);

    private final Supplier<SftpConfig> configSupplier;
    private volatile Session session;

    public SftpStorageService(Supplier<SftpConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    private synchronized ChannelSftp openChannel() throws JSchException {
        SftpConfig cfg = configSupplier.get();
        if (session == null || !session.isConnected()) {
            session = buildSession(cfg);
            session.connect(10_000);
        }
        ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
        ch.connect(10_000);
        return ch;
    }

    private Session buildSession(SftpConfig cfg) throws JSchException {
        JSch jsch = new JSch();
        if (cfg.privateKey() != null && !cfg.privateKey().isBlank()) {
            byte[] keyBytes = cfg.privateKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            jsch.addIdentity("key", keyBytes, null, null);
        }
        if (cfg.knownHosts() != null && !cfg.knownHosts().isBlank()) {
            byte[] khBytes = cfg.knownHosts().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            jsch.setKnownHosts(new ByteArrayInputStream(khBytes));
        }
        Session s = jsch.getSession(cfg.username(), cfg.host(), cfg.port());
        if (cfg.password() != null && !cfg.password().isBlank()) {
            s.setPassword(cfg.password());
        }
        // Accept unknown hosts by default (admin can supply known_hosts to lock it down)
        if (cfg.knownHosts() == null || cfg.knownHosts().isBlank()) {
            s.setConfig("StrictHostKeyChecking", "no");
        }
        return s;
    }

    private String remotePath(String key) {
        String base = configSupplier.get().basePath();
        if (base == null || base.isBlank()) base = "/";
        if (!base.endsWith("/")) base += "/";
        return base + key;
    }

    @Override
    public InputStream getInputStream(String key) throws IOException {
        try {
            ChannelSftp ch = openChannel();
            InputStream raw = ch.get(remotePath(key));
            // Wrap to close channel when stream is closed
            return new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        ch.disconnect();
                    }
                }
            };
        } catch (Exception e) {
            throw new IOException("SFTP read failed for " + key, e);
        }
    }

    @Override
    public OutputStream getOutputStream(String key) throws IOException {
        try {
            Path tmp = Files.createTempFile("qd-sftp-", ".tmp");
            OutputStream base = Files.newOutputStream(tmp);
            return new FilterOutputStream(base) {
                @Override
                public void close() throws IOException {
                    super.close();
                    try {
                        uploadFromFile(key, tmp);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                }
            };
        } catch (Exception e) {
            throw new IOException("SFTP write setup failed for " + key, e);
        }
    }

    private void uploadFromFile(String key, Path tmp) throws IOException {
        try {
            ChannelSftp ch = openChannel();
            try {
                ensureParentDirs(ch, remotePath(key));
                try (InputStream in = Files.newInputStream(tmp)) {
                    ch.put(in, remotePath(key));
                }
            } finally {
                ch.disconnect();
            }
        } catch (Exception e) {
            throw new IOException("SFTP upload failed for " + key, e);
        }
    }

    private void ensureParentDirs(ChannelSftp ch, String path) {
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : null;
        if (parent == null || parent.isBlank()) return;
        try {
            ch.mkdir(parent);
        } catch (SftpException ignored) { /* may already exist */ }
    }

    @Override
    public boolean exists(String key) {
        try {
            ChannelSftp ch = openChannel();
            try {
                ch.lstat(remotePath(key));
                return true;
            } catch (SftpException e) {
                return e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE;
            } finally {
                ch.disconnect();
            }
        } catch (Exception e) {
            logger.warn("SFTP exists check failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            ChannelSftp ch = openChannel();
            try {
                ch.rm(remotePath(key));
                return true;
            } catch (SftpException e) {
                return e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE;
            } finally {
                ch.disconnect();
            }
        } catch (Exception e) {
            logger.error("SFTP delete failed for {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        try {
            ChannelSftp ch = openChannel();
            try {
                String base = configSupplier.get().basePath();
                if (base == null || base.isBlank()) base = "/";
                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> entries = ch.ls(base);
                List<String> result = new ArrayList<>();
                for (ChannelSftp.LsEntry e : entries) {
                    if (!e.getAttrs().isDir() && e.getFilename().endsWith(suffix)) {
                        result.add(e.getFilename());
                    }
                }
                return result;
            } finally {
                ch.disconnect();
            }
        } catch (Exception e) {
            logger.error("SFTP list failed with suffix {}: {}", suffix, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public StorageBackend getBackend() {
        return StorageBackend.SFTP;
    }

    /**
     * Returns null on success, error message on failure.
     */
    public String testConnection() {
        try {
            synchronized (this) {
                if (session != null) {
                    try {
                        session.disconnect();
                    } catch (Exception ignored) {
                    }
                    session = null;
                }
            }
            ChannelSftp ch = openChannel();
            ch.disconnect();
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Override
    public boolean isReachable() {
        try {
            ChannelSftp ch = openChannel();
            ch.disconnect();
            return true;
        } catch (Exception e) {
            logger.debug("SFTP health probe failed: {}", e.getMessage());
            return false;
        }
    }

    public record SftpConfig(String host, int port, String username, String password, String privateKey,
                             String basePath, String knownHosts) {
    }
}
