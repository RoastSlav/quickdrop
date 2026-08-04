package org.rostislav.quickdrop.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the shared {@link StorageServiceContractTest} suite for real against a
 * {@code @TempDir} -- never against the repo's real {@code files/} directory.
 */
class LocalStorageServiceTest extends StorageServiceContractTest {

    @TempDir
    Path tempDir;

    private LocalStorageService service;

    @BeforeEach
    void setUp() {
        service = new LocalStorageService(() -> tempDir.toString());
    }

    @Override
    protected StorageService storageService() {
        return service;
    }

    @Test
    void backendIsLocal() {
        assertEquals(StorageBackend.LOCAL, service.getBackend());
    }

    @Test
    void isReachableIsAlwaysTrueForLocalDisk() {
        assertTrue(service.isReachable());
    }

    @Test
    void rootSupplierIsConsultedOnEveryCallSoPathChangesTakeEffectImmediately() throws IOException {
        Path firstRoot = tempDir.resolve("root-a");
        Path secondRoot = tempDir.resolve("root-b");
        Files.createDirectories(firstRoot);
        Files.createDirectories(secondRoot);

        java.util.concurrent.atomic.AtomicReference<Path> activeRoot = new java.util.concurrent.atomic.AtomicReference<>(firstRoot);
        LocalStorageService dynamicService = new LocalStorageService(() -> activeRoot.get().toString());

        try (OutputStream out = dynamicService.getOutputStream("switch-key")) {
            out.write("in-root-a".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(firstRoot.resolve("switch-key")));
        assertFalse(Files.exists(secondRoot.resolve("switch-key")));

        activeRoot.set(secondRoot);
        try (OutputStream out = dynamicService.getOutputStream("switch-key")) {
            out.write("in-root-b".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(secondRoot.resolve("switch-key")));

        try (InputStream in = dynamicService.getInputStream("switch-key")) {
            assertEquals("in-root-b", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void getOutputStreamCreatesParentDirectoriesForNestedKeys() throws IOException {
        // Keys are usually flat UUIDs, but the implementation resolves them as paths --
        // verify it doesn't blow up if a key happens to contain a path separator and
        // creates the intermediate directory rather than failing.
        String nestedKey = "sub/nested-key";
        try (OutputStream out = service.getOutputStream(nestedKey)) {
            out.write("nested".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(Files.exists(tempDir.resolve("sub").resolve("nested-key")));
    }
}
