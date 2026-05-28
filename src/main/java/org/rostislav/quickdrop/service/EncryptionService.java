package org.rostislav.quickdrop.service;

import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

/**
 * AES/CBC encryption and decryption service for uploaded files.
 *
 * <p>The on-disk layout is:
 * <pre>
 *   [16 bytes salt][16 bytes IV][AES-CBC ciphertext…]
 * </pre>
 * The AES key is derived from the user-supplied password and the stored salt
 * using PBKDF2/HMAC-SHA256 with {@value #ITERATION_COUNT} iterations and a
 * {@value #KEY_LENGTH}-bit output.
 */
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 128;

    /**
     * Derives a 128-bit AES key from a password and salt using PBKDF2/HMAC-SHA256.
     *
     * @param password the cleartext password
     * @param salt     16-byte random salt
     * @return the derived AES secret key
     * @throws NoSuchAlgorithmException if PBKDF2WithHmacSHA256 is unavailable
     * @throws InvalidKeySpecException  if the key specification is invalid
     */
    private SecretKey generateKeyFromPassword(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] keyBytes = keyFactory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Generates 16 cryptographically random bytes suitable for use as a salt or IV.
     *
     * @return 16 random bytes
     */
    private byte[] generateRandomBytes() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Returns an {@link InputStream} that transparently decrypts an AES/CBC-encrypted file.
     * The caller is responsible for closing the returned stream.
     *
     * @param inputFile the encrypted file to read
     * @param password  the cleartext password used at encryption time
     * @return a decrypting input stream positioned after the header
     * @throws Exception if the file cannot be opened or the cipher cannot be initialised
     */
    public InputStream getDecryptedInputStream(File inputFile, String password) throws Exception {
        FileInputStream fis = new FileInputStream(inputFile);
        try {
            byte[] salt = fis.readNBytes(16);
            byte[] iv = fis.readNBytes(16);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKey secretKey = generateKeyFromPassword(password, salt);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            return new CipherInputStream(fis, cipher);
        } catch (Exception e) {
            fis.close();
            throw e;
        }
    }

    /**
     * Returns an {@link OutputStream} that transparently encrypts data written to it
     * and appends the result to the given file, preceded by the salt and IV header.
     * <p>
     * The caller is responsible for closing the returned stream.
     *
     * @param finalFile the destination file (opened in append mode)
     * @param password  the cleartext password to encrypt with
     * @return an encrypting output stream
     * @throws Exception if the file cannot be opened or the cipher cannot be initialised
     */
    public OutputStream getEncryptedOutputStream(File finalFile, String password) throws Exception {
        FileOutputStream fos = new FileOutputStream(finalFile, true);
        try {
            byte[] salt = generateRandomBytes();
            byte[] iv = generateRandomBytes();
            fos.write(salt);
            fos.write(iv);
            SecretKey secretKey = generateKeyFromPassword(password, salt);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            return new CipherOutputStream(fos, cipher);
        } catch (Exception e) {
            fos.close();
            throw e;
        }
    }
}
