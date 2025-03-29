package org.rostislav.quickdrop.service;

import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

@Service
public class FileEncryptionService {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 128;

    public SecretKey generateKeyFromPassword(String password, byte[] salt)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] keyBytes = keyFactory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] generateRandomBytes() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void decryptFile(File inputFile, File outputFile, String password) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, InvalidKeyException {
        try (FileInputStream fis = new FileInputStream(inputFile)) {
            byte[] salt = new byte[16];
            byte[] iv = new byte[16];

            fis.read(salt);
            fis.read(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            SecretKey secretKey = generateKeyFromPassword(password, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            try (CipherInputStream cis = new CipherInputStream(fis, cipher);
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = cis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    public InputStream getDecryptedInputStream(File inputFile, String password) throws Exception {
        FileInputStream fis = new FileInputStream(inputFile);
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];

        fis.read(salt);
        fis.read(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        SecretKey secretKey = generateKeyFromPassword(password, salt);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        CipherInputStream cipherInputStream = new CipherInputStream(fis, cipher);
        return cipherInputStream;
    }

    public OutputStream getEncryptedOutputStream(File finalFile, String password) throws Exception {
        FileOutputStream fos = new FileOutputStream(finalFile, true);
        byte[] salt = generateRandomBytes();
        byte[] iv = generateRandomBytes();

        fos.write(salt);
        fos.write(iv);

        SecretKey secretKey = generateKeyFromPassword(password, salt);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
        return new CipherOutputStream(fos, cipher);
    }
}