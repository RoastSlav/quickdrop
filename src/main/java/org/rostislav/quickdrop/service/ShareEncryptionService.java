package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Handles re-encryption of file sidecars for share links in a background thread pool.
 *
 * <p>When a share link is created for an encrypted file the sidecar encryption is
 * submitted here so the HTTP request returns immediately. The share token is saved to
 * the database with {@code sidecarReady = false}; once this service completes the work
 * it flips the flag to {@code true}. If encryption fails the token is deleted so the
 * creator can try again rather than being left with a permanently broken link.
 */
@Service
public class ShareEncryptionService {
    private static final Logger logger = LoggerFactory.getLogger(ShareEncryptionService.class);

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final FileEncryptionService fileEncryptionService;
    private final ApplicationSettingsService applicationSettingsService;

    public ShareEncryptionService(FileEncryptionService fileEncryptionService,
                                  ApplicationSettingsService applicationSettingsService) {
        this.fileEncryptionService = fileEncryptionService;
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * Submits a background task that decrypts the original file and re-encrypts it
     * into a sidecar at {@code {storagePath}/{uuid}-share-{token}}.
     *
     * <p>On success: calls {@link ShareTokenRepository#markSidecarReady(Long)} to flip
     * only the {@code sidecar_ready} column to {@code true}, leaving all other columns
     * (including {@code created_at}) untouched.
     * On failure: logs the error, removes any partial sidecar file, and calls
     * {@link ShareTokenRepository#deleteByIdTransactional(Long)} so the creator can
     * retry without being left with a permanently broken link.
     *
     * @param uuid          the file UUID (used to locate the AES-encrypted original)
     * @param token         the share token string (used to name the sidecar)
     * @param shareKey      the plaintext share key used to encrypt the sidecar
     * @param plainPassword the plaintext file password used to decrypt the original
     * @param tokenId       the persisted token's database id
     * @param repo          the repository used to mark ready or delete the token
     */
    public void encryptSidecarAsync(String uuid, String token, String shareKey,
                                    String plainPassword, Long tokenId,
                                    ShareTokenRepository repo) {
        executor.submit(() -> {
            Path encryptedFilePath = Path.of(applicationSettingsService.getFileStoragePath(), uuid);
            Path sidecarPath = Path.of(applicationSettingsService.getFileStoragePath(), uuid + "-share-" + token);
            try {
                try (InputStream decIn = fileEncryptionService.getDecryptedInputStream(encryptedFilePath.toFile(), plainPassword);
                     OutputStream encOut = fileEncryptionService.getEncryptedOutputStream(sidecarPath.toFile(), shareKey)) {
                    decIn.transferTo(encOut);
                }
                logger.info("Background sidecar encryption complete for token: {}", token);
                repo.markSidecarReady(tokenId);
            } catch (Exception e) {
                logger.error("Background sidecar encryption failed for token {}: {}", token, e.getMessage());
                try {
                    Files.deleteIfExists(sidecarPath);
                } catch (Exception ignored) {
                }
                repo.deleteByIdTransactional(tokenId);
            }
        });
    }
}
