package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptionServiceTest {
    private static final String PASSWORD = "download-secret";
    private static final int ITERATION_COUNT = 65536;

    private final EncryptionService encryptionService = new EncryptionService();

    @Test
    void chunkedGcmRoundTripsLargePayload() throws Exception {
        byte[] plain = new byte[4 * 1024 * 1024 + 123];
        new SecureRandom(new byte[]{1, 2, 3, 4}).nextBytes(plain);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        try (var out = encryptionService.getEncryptedOutputStream(encrypted, PASSWORD)) {
            out.write(plain);
        }

        byte[] encryptedBytes = encrypted.toByteArray();
        assertEquals("QDG2", new String(encryptedBytes, 0, 4, StandardCharsets.UTF_8));

        byte[] decrypted;
        try (InputStream in = encryptionService.getDecryptedInputStream(new ByteArrayInputStream(encryptedBytes), PASSWORD)) {
            decrypted = in.readAllBytes();
        }
        assertArrayEquals(plain, decrypted);
    }

    @Test
    void legacyMonolithicGcmRoundTripsWithFragmentedReads() throws Exception {
        byte[] plain = new byte[128 * 1024 + 37];
        new SecureRandom(new byte[]{5, 6, 7, 8}).nextBytes(plain);
        byte[] encrypted = encryptLegacyMonolithicGcm(plain);

        byte[] decrypted;
        try (InputStream in = encryptionService.getDecryptedInputStream(fragmented(encrypted, 5), PASSWORD)) {
            decrypted = in.readAllBytes();
        }
        assertArrayEquals(plain, decrypted);
    }

    @Test
    void legacyMonolithicGcmDetectsTamperingAtEndOfStream() throws Exception {
        byte[] encrypted = encryptLegacyMonolithicGcm("hello".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 0x01;

        try (InputStream in = encryptionService.getDecryptedInputStream(new ByteArrayInputStream(encrypted), PASSWORD)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
    }

    private static byte[] encryptLegacyMonolithicGcm(byte[] plain) throws Exception {
        byte[] salt = new byte[32];
        byte[] iv = new byte[12];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (i + 1);
        }
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (i + 33);
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(PASSWORD, salt), new GCMParameterSpec(128, iv));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("QDGM".getBytes(StandardCharsets.UTF_8));
        out.write(salt);
        out.write(iv);
        out.write(cipher.doFinal(plain));
        return out.toByteArray();
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, 256);
        byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static InputStream fragmented(byte[] data, int maxChunkSize) {
        return new FilterInputStream(new ByteArrayInputStream(Arrays.copyOf(data, data.length))) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, maxChunkSize));
            }
        };
    }

    // -------------------------------------------------------------------------
    // Chunked AES/GCM format: [QDG2 4B][salt 32B][baseIV 12B][chunk size 4B]
    // then repeated [encrypted chunk length 4B][ciphertext+tag]. Header is 52 bytes.
    // -------------------------------------------------------------------------

    private static final int CHUNKED_HEADER_LENGTH = 52; // 4 + 32 + 12 + 4
    private static final int GCM_TAG_BYTES = 16;

    private byte[] encryptChunked(byte[] plain) throws Exception {
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        try (var out = encryptionService.getEncryptedOutputStream(encrypted, PASSWORD)) {
            out.write(plain);
        }
        return encrypted.toByteArray();
    }

    @Test
    void chunkedGcm_truncatedMidChunk_throwsEofException() throws Exception {
        byte[] encrypted = encryptChunked("a plaintext long enough to leave a real chunk body".getBytes(StandardCharsets.UTF_8));
        // Drop the last 5 bytes of ciphertext while leaving the length prefix claiming the
        // original (larger) size -- readExact() must fail fast rather than return short data.
        byte[] truncated = Arrays.copyOf(encrypted, encrypted.length - 5);

        try (InputStream in = encryptionService.getDecryptedInputStream(new ByteArrayInputStream(truncated), PASSWORD)) {
            IOException thrown = assertThrows(IOException.class, in::readAllBytes);
            assertTrue(thrown instanceof java.io.EOFException, "expected EOFException, got " + thrown.getClass());
        }
    }

    @Test
    void chunkedGcm_invalidChunkLengthPrefix_isRejected() throws Exception {
        byte[] encrypted = encryptChunked("hello".getBytes(StandardCharsets.UTF_8));
        // Overwrite the first chunk's 4-byte length prefix (right after the 52-byte header)
        // with a value below GCM_TAG_BYTES -- too small to ever be a valid chunk.
        ByteBuffer.wrap(encrypted, CHUNKED_HEADER_LENGTH, Integer.BYTES).putInt(GCM_TAG_BYTES - 1);

        try (InputStream in = encryptionService.getDecryptedInputStream(new ByteArrayInputStream(encrypted), PASSWORD)) {
            IOException thrown = assertThrows(IOException.class, in::readAllBytes);
            assertTrue(thrown.getMessage().contains("Invalid encrypted chunk length"), thrown.getMessage());
        }
    }

    @Test
    void chunkedGcm_invalidChunkSizeInHeader_isRejected() throws Exception {
        byte[] encrypted = encryptChunked("hello".getBytes(StandardCharsets.UTF_8));
        // Chunk-size field is the last 4 bytes of the header (offset 48-51). Zero is rejected
        // by the "<= 0" half of buildDecryptingStream's bounds check.
        ByteBuffer.wrap(encrypted, CHUNKED_HEADER_LENGTH - Integer.BYTES, Integer.BYTES).putInt(0);

        Exception thrown = assertThrows(IOException.class,
                () -> encryptionService.getDecryptedInputStream(new ByteArrayInputStream(encrypted), PASSWORD));
        assertTrue(thrown.getMessage().contains("Invalid encrypted chunk size"), thrown.getMessage());
    }

    @Test
    void chunkedGcm_tamperedByteInNonFinalChunk_isDetected() throws Exception {
        // legacyMonolithicGcmDetectsTamperingAtEndOfStream above only proves the legacy
        // single-message format detects tampering at the very end. The chunked format
        // authenticates each chunk independently, so this proves a chunk that ISN'T the
        // last one is also protected, not just the tail of the overall stream.
        byte[] plain = new byte[4 * 1024 * 1024 + 123];
        new SecureRandom(new byte[]{9, 9, 9, 9}).nextBytes(plain);
        byte[] encrypted = encryptChunked(plain);
        // First chunk's ciphertext starts right after header + its own length prefix.
        int firstChunkCiphertextStart = CHUNKED_HEADER_LENGTH + Integer.BYTES;
        encrypted[firstChunkCiphertextStart + 100] ^= 0x01;

        try (InputStream in = encryptionService.getDecryptedInputStream(new ByteArrayInputStream(encrypted), PASSWORD)) {
            IOException thrown = assertThrows(IOException.class, in::readAllBytes);
            assertTrue(thrown.getMessage().contains("authentication failed"), thrown.getMessage());
        }
    }

    @Test
    void chunkedGcmOutputStream_writeAfterClose_throws() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream out = encryptionService.getEncryptedOutputStream(sink, PASSWORD);
        out.close();

        IOException thrown = assertThrows(IOException.class, () -> out.write('x'));
        assertTrue(thrown.getMessage().contains("already closed"), thrown.getMessage());
    }

    @Test
    void chunkedGcmOutputStream_doubleClose_isIdempotent() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream out = encryptionService.getEncryptedOutputStream(sink, PASSWORD);
        out.write("content".getBytes(StandardCharsets.UTF_8));
        out.close();

        assertDoesNotThrow(out::close);
    }

    @Test
    void chunkedGcmInputStream_readAfterClose_throws() throws Exception {
        byte[] encrypted = encryptChunked("hello".getBytes(StandardCharsets.UTF_8));
        InputStream in = encryptionService.getDecryptedInputStream(new ByteArrayInputStream(encrypted), PASSWORD);
        in.close();

        IOException thrown = assertThrows(IOException.class, in::read);
        assertTrue(thrown.getMessage().contains("already closed"), thrown.getMessage());
    }

    /**
     * A target {@link OutputStream} that can be told to fail write() after a byte threshold
     * (so the encrypted header can succeed before a later data write fails) and/or fail
     * close(), to exercise {@code ChunkedGcmOutputStream#close()}'s suppressed-exception path.
     */
    private static class FailingOutputStream extends FilterOutputStream {
        private final int failAfterByteCount;
        private final boolean failOnClose;
        private int written = 0;

        FailingOutputStream(OutputStream out, int failAfterByteCount, boolean failOnClose) {
            super(out);
            this.failAfterByteCount = failAfterByteCount;
            this.failOnClose = failOnClose;
        }

        @Override
        public void write(int b) throws IOException {
            if (written++ >= failAfterByteCount) {
                throw new IOException("simulated write failure");
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written + len > failAfterByteCount) {
                written += len;
                throw new IOException("simulated write failure");
            }
            written += len;
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (failOnClose) {
                throw new IOException("simulated close failure");
            }
            out.close();
        }
    }

    @Test
    void chunkedGcmOutputStream_writeFailureDuringFinalFlush_suppressesSubsequentCloseFailure() throws Exception {
        // failAfterByteCount=60 lets the 52-byte header succeed; the data write triggered by
        // close() (flushing the buffered plaintext as the final chunk) is what fails.
        FailingOutputStream failing = new FailingOutputStream(new ByteArrayOutputStream(), 60, true);
        OutputStream out = encryptionService.getEncryptedOutputStream(failing, PASSWORD);
        out.write("some plaintext that gets buffered until close() flushes it".getBytes(StandardCharsets.UTF_8));

        IOException thrown = assertThrows(IOException.class, out::close);
        assertEquals("simulated write failure", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length,
                "target.close()'s own failure must be attached as suppressed, not lost or thrown separately");
        assertEquals("simulated close failure", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void chunkedGcmOutputStream_closeFailureAlone_propagatesDirectly() throws Exception {
        FailingOutputStream failing = new FailingOutputStream(new ByteArrayOutputStream(), Integer.MAX_VALUE, true);
        OutputStream out = encryptionService.getEncryptedOutputStream(failing, PASSWORD);
        out.write("small content".getBytes(StandardCharsets.UTF_8));

        IOException thrown = assertThrows(IOException.class, out::close);
        assertEquals("simulated close failure", thrown.getMessage());
        assertEquals(0, thrown.getSuppressed().length);
    }
}
