package org.rostislav.quickdrop.util;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

public class FileEncryptionUtils {
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 128;

    public static SecretKey generateKeyFromPassword(String password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = keyFactory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static byte[] generateRandomBytes() {
        byte[] salt = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        return salt;
    }

    public static void encryptFile(File inputFile, File outputFile, String password) throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidAlgorithmParameterException, InvalidKeyException, IOException, IllegalBlockSizeException, BadPaddingException {
        byte[] salt = generateRandomBytes();
        SecretKey secretKey = generateKeyFromPassword(password, salt);


        byte[] iv = generateRandomBytes();
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(salt);
            outputStream.write(iv);
            try (FileInputStream inputStream = new FileInputStream(inputFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byte[] encrypted = cipher.update(buffer, 0, bytesRead);
                    if (encrypted != null) {
                        outputStream.write(encrypted);
                    }
                }
                byte[] encrypted = cipher.doFinal();
                if (encrypted != null) {
                    outputStream.write(encrypted);
                }
            }
        }
    }

    public static void decryptFile(File inputFile, File outputFile, String password) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, NoSuchPaddingException {
        try (FileInputStream inputStream = new FileInputStream(inputFile)) {
            byte[] salt = new byte[16];
            inputStream.read(salt);

            byte[] iv = new byte[16];
            inputStream.read(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            SecretKey secretKey = generateKeyFromPassword(password, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byte[] decrypted = cipher.update(buffer, 0, bytesRead);
                    if (decrypted != null) {
                        outputStream.write(decrypted);
                    }
                }
                byte[] decrypted = cipher.doFinal();
                if (decrypted != null) {
                    outputStream.write(decrypted);
                }
            }
        }
    }
}
