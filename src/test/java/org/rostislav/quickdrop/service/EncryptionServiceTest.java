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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
