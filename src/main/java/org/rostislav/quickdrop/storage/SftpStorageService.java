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
 * <p>Uses a {@link Supplier} config. Each I/O operation creates its own SSH
 * {@link Session} and {@link ChannelSftp}, performs the work, then closes both
 * in a finally block. This eliminates all shared-state concurrency races at the
 * cost of one reconnect per operation.
 *
 * <p>Writes buffer to a local temp file then upload on {@link OutputStream#close()}
 * to avoid holding an SSH channel open for the full transfer duration.
 */
public class SftpStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(SftpStorageService.class);

    private final Supplier<SftpConfig> configSupplier;

    public SftpStorageService(Supplier<SftpConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /**
     * Fix 1 &amp; 2: Creates a fresh {@link Session} per call (no shared state).
     * When knownHosts is blank, {@code StrictHostKeyChecking=no} is used but a
     * prominent WARNING is logged on every connection attempt. When knownHosts IS
     * configured, {@code StrictHostKeyChecking=yes} is set explicitly.
     */
    private Session createSession() throws JSchException {
        SftpConfig cfg = configSupplier.get();
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
        if (cfg.knownHosts() == null || cfg.knownHosts().isBlank()) {
            // Fix 1: Warn loudly on every connection when host key verification is off.
            logger.warn("SFTP WARNING: knownHosts is not configured. Host key verification is DISABLED. " +
                    "Configure sftpKnownHosts in settings to enable host verification and prevent MITM attacks.");
            s.setConfig("StrictHostKeyChecking", "no");
        } else {
            // Fix 1: Explicitly enforce strict checking when knownHosts is provided.
            s.setConfig("StrictHostKeyChecking", "yes");
        }
        s.connect(10_000);
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
            Session s = createSession();
            ChannelSftp ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
            InputStream raw = ch.get(remotePath(key));
            // Wrap to close channel and disconnect session when the stream is closed.
            return new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        try { ch.disconnect(); } catch (Exception ignored) {}
                        try { s.disconnect(); } catch (Exception ignored) {}
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
        Session s = null;
        ChannelSftp ch = null;
        try {
            s = createSession();
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
            ensureParentDirs(ch, remotePath(key));
            try (InputStream in = Files.newInputStream(tmp)) {
                ch.put(in, remotePath(key));
            }
        } catch (Exception e) {
            throw new IOException("SFTP upload failed for " + key, e);
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * Fix 3: Walk each path component individually so multi-level paths are created.
     */
    private void ensureParentDirs(ChannelSftp ch, String path) {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) continue;
            current.append("/").append(parts[i]);
            try {
                ch.mkdir(current.toString());
            } catch (SftpException e) {
                // Directory likely already exists — ignore
            }
        }
    }

    @Override
    public boolean exists(String key) {
        Session s = null;
        ChannelSftp ch = null;
        try {
            s = createSession();
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
            try {
                ch.lstat(remotePath(key));
                return true;
            } catch (SftpException e) {
                return e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE;
            }
        } catch (Exception e) {
            logger.warn("SFTP exists check failed for {}: {}", key, e.getMessage());
            return false;
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean delete(String key) {
        Session s = null;
        ChannelSftp ch = null;
        try {
            s = createSession();
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
            try {
                ch.rm(remotePath(key));
                return true;
            } catch (SftpException e) {
                return e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE;
            }
        } catch (Exception e) {
            logger.error("SFTP delete failed for {}: {}", key, e.getMessage());
            return false;
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        Session s = null;
        ChannelSftp ch = null;
        try {
            s = createSession();
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
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
        } catch (Exception e) {
            logger.error("SFTP list failed with suffix {}: {}", suffix, e.getMessage());
            return Collections.emptyList();
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
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
        Session s = null;
        ChannelSftp ch = null;
        try {
            s = createSession();
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean isReachable() {
        Session s = null;
        ChannelSftp ch = null;
        try {
            s = createSession();
            ch = (ChannelSftp) s.openChannel("sftp");
            ch.connect(10_000);
            return true;
        } catch (Exception e) {
            logger.debug("SFTP health probe failed: {}", e.getMessage());
            return false;
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Exception ignored) {}
            if (s != null) try { s.disconnect(); } catch (Exception ignored) {}
        }
    }

    public record SftpConfig(String host, int port, String username, String password, String privateKey,
                             String basePath, String knownHosts) {
    }
}
