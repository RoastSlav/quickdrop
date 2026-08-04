package org.rostislav.quickdrop.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;

/**
 * Generates the larger/derived test fixtures at runtime rather than committing multi-MB
 * binaries to git. Small deterministic fixtures (plain text, 0-byte, unicode filename,
 * GPS-tagged JPEG, XSS SVG) live as real files under src/test/resources/fixtures/ instead
 * -- see scripts/generate-test-fixtures.py for how the binary ones were produced.
 */
public final class FixtureFiles {

    /**
     * Mirrors {@code UPLOAD_CHUNK_SIZE} in
     * src/main/resources/static/js/upload/network.js -- the client splits uploads into
     * 4 MB pieces (not 1 MB, despite what AGENTS.md currently says).
     */
    public static final int UPLOAD_CHUNK_SIZE = 4 * 1024 * 1024;

    private FixtureFiles() {
    }

    /**
     * Deterministic (seeded) payload sized to span exactly 3 chunks at
     * {@link #UPLOAD_CHUNK_SIZE} (4 + 4 + 1 MB), for chunked-upload round-trip tests.
     */
    public static byte[] payloadCrossingChunkBoundary() {
        return deterministicBytes(2 * UPLOAD_CHUNK_SIZE + UPLOAD_CHUNK_SIZE / 4, 42L);
    }

    /**
     * Deterministic byte array of the given size, reproducible across runs for a given seed
     * so hash-based round-trip assertions are stable.
     */
    public static byte[] deterministicBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    /**
     * Writes a 5-file nested folder tree under {@code root} with deterministic content,
     * for folder-upload / manifest tests. Returns the created files' paths relative to root,
     * using forward slashes.
     */
    public static List<String> writeNestedFolder(Path root) throws IOException {
        record Entry(String relativePath, int size) {
        }
        List<Entry> entries = List.of(
                new Entry("readme.txt", 512),
                new Entry("docs/notes.md", 2048),
                new Entry("docs/nested/deep.txt", 256),
                new Entry("images/placeholder.bin", 4096),
                new Entry("empty-file.txt", 0)
        );

        for (Entry entry : entries) {
            Path filePath = root.resolve(entry.relativePath());
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, deterministicBytes(entry.size(), entry.relativePath().hashCode()));
        }

        return entries.stream().map(Entry::relativePath).toList();
    }

    public static String sha256Hex(byte[] data) {
        return sha256Hex(new java.io.ByteArrayInputStream(data));
    }

    public static String sha256Hex(InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to hash stream", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Path classpathFixture(String name) {
        try {
            var url = FixtureFiles.class.getClassLoader().getResource("fixtures/" + name);
            if (url == null) {
                throw new IllegalArgumentException("No such fixture: " + name);
            }
            return Path.of(url.toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Reads a small checked-in fixture as UTF-8 text (e.g. the SVG/XSS payload). */
    public static String readFixtureText(String name) throws IOException {
        return Files.readString(classpathFixture(name), StandardCharsets.UTF_8);
    }
}
