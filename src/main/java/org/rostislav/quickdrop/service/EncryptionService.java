package org.rostislav.quickdrop.service;

import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/**
 * Encryption and decryption service for uploaded files.
 *
 * <p>New files are encrypted with AES/GCM/NoPadding (AES-256). The on-disk layout is:
 * <pre>
 *   [QDGM 4B][salt 32B][iv 12B][AES-GCM ciphertext+tag…]
 * </pre>
 *
 * <p>Legacy files encrypted with AES/CBC/PKCS5Padding (AES-128) are detected by the
 * absence of the "QDGM" magic prefix and decrypted transparently. Their on-disk layout is:
 * <pre>
 *   [salt 16B][iv 16B][AES-CBC ciphertext…]
 * </pre>
 *
 * The AES key is derived from the user-supplied password and the stored salt
 * using PBKDF2/HMAC-SHA256 with {@value #ITERATION_COUNT} iterations.
 */
@Service
public class EncryptionService {

    // Legacy CBC constants (kept for backward-compat decryption)
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int SALT_LENGTH = 16;   // legacy: 16-byte salt
    private static final int IV_LENGTH = 16;      // legacy: 16-byte CBC IV

    // New GCM constants
    private static final byte[] MAGIC = "QDGM".getBytes(StandardCharsets.UTF_8);
    private static final int MAGIC_LENGTH = 4;
    private static final int GCM_SALT_LENGTH = 32; // 32-byte salt for new files
    private static final int GCM_IV_LENGTH = 12;   // 12-byte IV (GCM standard)
    private static final int GCM_TAG_BITS = 128;
    private static final String GCM_ALGORITHM = "AES/GCM/NoPadding";

    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256; // AES-256 for new files

    // Fix 3: single static SecureRandom instance to avoid repeated OS entropy pool hits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Derives an AES key of the given bit length from a password and salt using
     * PBKDF2/HMAC-SHA256.
     *
     * @param password  the cleartext password
     * @param salt      random salt bytes
     * @param keyLength desired key length in bits (128 for legacy CBC, 256 for new GCM)
     * @return the derived AES secret key
     */
    private SecretKey deriveKey(String password, byte[] salt, int keyLength)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, keyLength);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] keyBytes = keyFactory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Generates {@code length} cryptographically random bytes using the shared
     * {@link SecureRandom} instance.
     */
    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    // -------------------------------------------------------------------------
    // Decryption
    // -------------------------------------------------------------------------

    /**
     * Returns an {@link InputStream} that transparently decrypts the given stream.
     *
     * <p>Detects the format by peeking at the first {@value #MAGIC_LENGTH} bytes:
     * <ul>
     *   <li>If they equal "QDGM" → new AES/GCM format (AES-256, 32-byte salt, 12-byte IV).
     *   <li>Otherwise → legacy AES/CBC format (AES-128, 16-byte salt, 16-byte IV); the
     *       peeked bytes are pushed back before reading the header.
     * </ul>
     *
     * @param source   the encrypted input stream positioned at the very start
     * @param password the cleartext password used at encryption time
     * @return a decrypting input stream positioned after the header
     * @throws Exception if the cipher cannot be initialised
     */
    public InputStream getDecryptedInputStream(InputStream source, String password) throws Exception {
        PushbackInputStream pb = new PushbackInputStream(source, MAGIC_LENGTH);
        try {
            return buildDecryptingStream(pb, password);
        } catch (Exception e) {
            pb.close();
            throw e;
        }
    }

    /**
     * Returns an {@link InputStream} that transparently decrypts an AES-encrypted file.
     * The caller is responsible for closing the returned stream.
     *
     * @param inputFile the encrypted file to read
     * @param password  the cleartext password used at encryption time
     * @return a decrypting input stream positioned after the header
     * @throws Exception if the file cannot be opened or the cipher cannot be initialised
     */
    public InputStream getDecryptedInputStream(File inputFile, String password) throws Exception {
        FileInputStream fis = new FileInputStream(inputFile);
        PushbackInputStream pb = new PushbackInputStream(fis, MAGIC_LENGTH);
        try {
            return buildDecryptingStream(pb, password);
        } catch (Exception e) {
            pb.close();
            throw e;
        }
    }

    /**
     * Core format-detecting decrypt logic shared by both {@code getDecryptedInputStream}
     * overloads.
     */
    private InputStream buildDecryptingStream(PushbackInputStream pb, String password) throws Exception {
        byte[] peek = pb.readNBytes(MAGIC_LENGTH);
        boolean isNewFormat = peek.length == MAGIC_LENGTH && Arrays.equals(peek, MAGIC);

        if (isNewFormat) {
            // New GCM format: magic already consumed, read salt + IV + ciphertext
            byte[] salt = pb.readNBytes(GCM_SALT_LENGTH);
            byte[] iv   = pb.readNBytes(GCM_IV_LENGTH);
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new CipherInputStream(pb, cipher);
        } else {
            // Legacy CBC format: push peeked bytes back, then read 16-byte salt + 16-byte IV
            pb.unread(peek);
            byte[] salt = pb.readNBytes(SALT_LENGTH);
            byte[] iv   = pb.readNBytes(IV_LENGTH);
            SecretKey key = deriveKey(password, salt, 128); // old files used AES-128
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return new CipherInputStream(pb, cipher);
        }
    }

    // -------------------------------------------------------------------------
    // Encryption
    // -------------------------------------------------------------------------

    /**
     * Returns an {@link OutputStream} that transparently encrypts data written to it
     * using AES/GCM/NoPadding (AES-256), writing the QDGM magic header, 32-byte salt,
     * 12-byte IV, and then the GCM ciphertext+tag into {@code target}.
     * The caller is responsible for closing the returned stream.
     *
     * @param target   the destination stream
     * @param password the cleartext password to encrypt with
     * @return an encrypting output stream wrapping {@code target}
     * @throws Exception if the cipher cannot be initialised
     */
    public OutputStream getEncryptedOutputStream(OutputStream target, String password) throws Exception {
        try {
            byte[] salt = generateRandomBytes(GCM_SALT_LENGTH);
            byte[] iv   = generateRandomBytes(GCM_IV_LENGTH);
            target.write(MAGIC);
            target.write(salt);
            target.write(iv);
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new CipherOutputStream(target, cipher);
        } catch (Exception e) {
            target.close();
            throw e;
        }
    }

    /**
     * Returns an {@link OutputStream} that transparently encrypts data written to it
     * and writes the result into {@code finalFile} (overwriting any existing content).
     * Uses AES/GCM/NoPadding (AES-256) with the QDGM magic header.
     *
     * <p>The file is opened in truncate mode (not append) to prevent stale ciphertext
     * being accumulated if a prior partial write existed (Fix 4).
     *
     * @param finalFile the destination file (opened in truncate/overwrite mode)
     * @param password  the cleartext password to encrypt with
     * @return an encrypting output stream
     * @throws Exception if the file cannot be opened or the cipher cannot be initialised
     */
    public OutputStream getEncryptedOutputStream(File finalFile, String password) throws Exception {
        // Fix 4: open in truncate mode (no second arg = false), not append mode (true)
        FileOutputStream fos = new FileOutputStream(finalFile);
        try {
            byte[] salt = generateRandomBytes(GCM_SALT_LENGTH);
            byte[] iv   = generateRandomBytes(GCM_IV_LENGTH);
            fos.write(MAGIC);
            fos.write(salt);
            fos.write(iv);
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new CipherOutputStream(fos, cipher);
        } catch (Exception e) {
            fos.close();
            throw e;
        }
    }
}
