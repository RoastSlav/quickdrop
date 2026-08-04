package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.storage.StorageService;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ShareEncryptionService}'s background sidecar re-encryption: successful
 * generation + key verification, and the failure/cleanup path when the source
 * decryption password is wrong.
 */
class ShareEncryptionServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private ShareEncryptionService shareEncryptionService;
    @Autowired
    private EncryptionService encryptionService;
    @Autowired
    private StorageService storageService;
    @Autowired
    private ShareTokenRepository shareTokenRepository;
    @Autowired
    private FileRepository fileRepository;

    private StoredFile persistEncryptedFile(String plaintext, String password) throws Exception {
        StoredFile file = new StoredFile();
        file.uuid = UUID.randomUUID().toString();
        file.name = "secret.bin";
        file.size = plaintext.length();
        file.encrypted = true;
        fileRepository.save(file);
        try (var out = encryptionService.getEncryptedOutputStream(storageService.getOutputStream(file.uuid), password)) {
            out.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    @Timeout(15)
    void sidecarIsGeneratedAndDecryptableWithTheShareKey() throws Exception {
        StoredFile file = persistEncryptedFile("top secret payload", "file-password");
        ShareTokenEntity token = new ShareTokenEntity("shr01", file, null, null);
        token.sidecarReady = false;
        shareTokenRepository.save(token);
        String shareKey = "share-key-123";
        String sidecarKey = file.uuid + "-share-" + token.shareToken;

        shareEncryptionService.encryptSidecarAsync(file.uuid, token.shareToken, shareKey, "file-password",
                token.getId(), shareTokenRepository);

        assertTrue(pollUntilSidecarReady(token.getId()), "sidecar encryption should complete within the timeout");
        assertTrue(storageService.exists(sidecarKey));

        try (InputStream decrypted = encryptionService.getDecryptedInputStream(storageService.getInputStream(sidecarKey), shareKey)) {
            String content = new String(decrypted.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("top secret payload", content);
        }
    }

    @Test
    @Timeout(15)
    void sidecarRejectsTheWrongShareKey() throws Exception {
        StoredFile file = persistEncryptedFile("another payload", "file-password-2");
        ShareTokenEntity token = new ShareTokenEntity("shr02", file, null, null);
        token.sidecarReady = false;
        shareTokenRepository.save(token);
        String correctShareKey = "correct-share-key";
        String sidecarKey = file.uuid + "-share-" + token.shareToken;

        shareEncryptionService.encryptSidecarAsync(file.uuid, token.shareToken, correctShareKey, "file-password-2",
                token.getId(), shareTokenRepository);
        assertTrue(pollUntilSidecarReady(token.getId()));

        Exception ex = assertThrows(Exception.class, () -> {
            try (InputStream decrypted = encryptionService.getDecryptedInputStream(
                    storageService.getInputStream(sidecarKey), "totally-wrong-key")) {
                decrypted.readAllBytes();
            }
        });
        assertNotNull(ex);
    }

    @Test
    @Timeout(15)
    void failedSourceDecryptionDeletesTheTokenAndAnyPartialSidecar() throws Exception {
        StoredFile file = persistEncryptedFile("payload", "real-password");
        ShareTokenEntity token = new ShareTokenEntity("shr03", file, null, null);
        token.sidecarReady = false;
        shareTokenRepository.save(token);
        Long tokenId = token.getId();
        String sidecarKey = file.uuid + "-share-" + token.shareToken;

        // Wrong plainPassword means decrypting the *original* file fails inside the
        // background task -- the token should be removed rather than left dangling
        // with sidecarReady stuck at false forever.
        shareEncryptionService.encryptSidecarAsync(file.uuid, token.shareToken, "some-share-key",
                "wrong-original-password", tokenId, shareTokenRepository);

        long deadline = System.currentTimeMillis() + 5000;
        boolean deleted = false;
        while (System.currentTimeMillis() < deadline) {
            if (shareTokenRepository.findById(tokenId).isEmpty()) {
                deleted = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(deleted, "a token whose sidecar encryption failed must be deleted, not left half-ready");
        assertFalse(storageService.exists(sidecarKey), "no partial sidecar should be left behind on failure");
    }

    private boolean pollUntilSidecarReady(Long tokenId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            boolean ready = shareTokenRepository.findById(tokenId).map(t -> t.sidecarReady).orElse(false);
            if (ready) return true;
            Thread.sleep(50);
        }
        return false;
    }
}
