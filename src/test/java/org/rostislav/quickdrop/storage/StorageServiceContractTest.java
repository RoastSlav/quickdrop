package org.rostislav.quickdrop.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract for every {@link StorageService} implementation: store / read /
 * delete / exists / missing-key behaviour (test plan Layer 1 §3.2).
 *
 * <p>Subclasses supply a concrete, ready-to-use {@link StorageService} via
 * {@link #storageService()}. {@link LocalStorageServiceTest} runs this for real against
 * a {@code @TempDir}. Cloud backends (S3/Azure/SFTP/WebDAV) are intentionally NOT
 * subclassed here: exercising them for real would require live credentials or
 * Testcontainers/MinIO (Docker is not guaranteed available in this sandbox), and their
 * client-boundary behaviour is instead covered indirectly via
 * {@link DelegatingStorageServiceTest}'s routing tests with mocked {@code StorageService}
 * beans, per the test-plan instruction to mock cloud SDKs at their client boundary
 * rather than skip coverage silently.
 */
abstract class StorageServiceContractTest {

    protected abstract StorageService storageService();

    private static byte[] bytesOf(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void store(String key, String content) throws IOException {
        try (OutputStream out = storageService().getOutputStream(key)) {
            out.write(bytesOf(content));
        }
    }

    private String readAll(String key) throws IOException {
        try (InputStream in = storageService().getInputStream(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void storedContentRoundTripsExactly() throws IOException {
        store("round-trip-key", "hello quickdrop");
        assertEquals("hello quickdrop", readAll("round-trip-key"));
    }

    @Test
    void existsIsFalseForKeyThatWasNeverWritten() {
        assertFalse(storageService().exists("never-written-key"));
    }

    @Test
    void existsIsTrueAfterWriting() throws IOException {
        store("exists-key", "x");
        assertTrue(storageService().exists("exists-key"));
    }

    @Test
    void readingMissingKeyThrowsIOException() {
        assertThrows(IOException.class, () -> storageService().getInputStream("does-not-exist-anywhere"));
    }

    @Test
    void deleteOfMissingKeyIsIdempotentSuccess() {
        // Per the StorageService contract, deleting an already-absent key is a success,
        // not an error -- callers must not need to check existence first.
        assertTrue(storageService().delete("was-never-there"));
    }

    @Test
    void deleteRemovesStoredObject() throws IOException {
        store("to-delete", "bye");
        assertTrue(storageService().exists("to-delete"));

        assertTrue(storageService().delete("to-delete"));

        assertFalse(storageService().exists("to-delete"));
    }

    @Test
    void deletingTwiceIsStillSuccessful() throws IOException {
        store("delete-twice", "content");
        assertTrue(storageService().delete("delete-twice"));
        assertTrue(storageService().delete("delete-twice"), "second delete of an already-gone key must still report success");
    }

    @Test
    void overwritingExistingKeyReplacesContent() throws IOException {
        store("overwrite-key", "first version, quite a bit longer than the second");
        store("overwrite-key", "v2");
        assertEquals("v2", readAll("overwrite-key"));
    }

    @Test
    void listKeySuffixFindsOnlyMatchingKeys() throws IOException {
        store("alpha-decrypted", "a");
        store("beta-decrypted", "b");
        store("gamma-share-xyz12", "g");

        var suffixMatches = storageService().listKeySuffix("-decrypted");

        assertTrue(suffixMatches.contains("alpha-decrypted"));
        assertTrue(suffixMatches.contains("beta-decrypted"));
        assertFalse(suffixMatches.contains("gamma-share-xyz12"));
    }

    @Test
    void emptyContentRoundTrips() throws IOException {
        store("empty-key", "");
        assertEquals("", readAll("empty-key"));
        assertTrue(storageService().exists("empty-key"));
    }

    @Test
    void binaryContentRoundTripsExactly() throws IOException {
        byte[] binary = new byte[512];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i % 256);
        }
        try (OutputStream out = storageService().getOutputStream("binary-key")) {
            out.write(binary);
        }
        byte[] readBack;
        try (InputStream in = storageService().getInputStream("binary-key")) {
            readBack = in.readAllBytes();
        }
        assertArrayEquals(binary, readBack);
    }
}
