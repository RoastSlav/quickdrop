package org.rostislav.quickdrop.service;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encryption and decryption service for uploaded files.
 *
 * <p>New files are stored as independent AES/GCM chunks:
 * <pre>
 *   [QDG2 4B][salt 32B][base IV 12B][chunk size 4B]
 *   repeated: [encrypted chunk length 4B][AES-GCM ciphertext + tag]
 * </pre>
 *
 * <p>Older QuickDrop 2.0 pre-release files used one large AES/GCM message:
 * <pre>
 *   [QDGM 4B][salt 32B][IV 12B][AES-GCM ciphertext + tag]
 * </pre>
 * Those files are still supported through a bounded-memory legacy stream. Legacy
 * AES/CBC files, which have no magic header, are also still supported.
 */
@Service
public class EncryptionService {

    private static final String LEGACY_CBC_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int LEGACY_CBC_SALT_LENGTH = 16;
    private static final int LEGACY_CBC_IV_LENGTH = 16;

    private static final byte[] MONOLITHIC_GCM_MAGIC = "QDGM".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHUNKED_GCM_MAGIC = "QDG2".getBytes(StandardCharsets.UTF_8);
    private static final int MAGIC_LENGTH = 4;
    private static final int GCM_SALT_LENGTH = 32;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final int GCM_CHUNK_SIZE = 4 * 1024 * 1024;
    private static final String GCM_ALGORITHM = "AES/GCM/NoPadding";

    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static byte[] readExact(InputStream in, int length, String label) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new EOFException("Unexpected end of encrypted stream while reading " + label);
        }
        return data;
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    public InputStream getDecryptedInputStream(InputStream source, String password) throws Exception {
        PushbackInputStream pb = new PushbackInputStream(source, MAGIC_LENGTH);
        try {
            return buildDecryptingStream(pb, password);
        } catch (Exception e) {
            pb.close();
            throw e;
        }
    }

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

    private static int readInt(InputStream in, String label) throws IOException {
        return ByteBuffer.wrap(readExact(in, Integer.BYTES, label)).getInt();
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static byte[] chunkIv(byte[] baseIv, long chunkIndex) {
        byte[] iv = baseIv.clone();
        for (int i = 0; i < Long.BYTES; i++) {
            int shift = (Long.BYTES - 1 - i) * 8;
            iv[GCM_IV_LENGTH - Long.BYTES + i] ^= (byte) (chunkIndex >>> shift);
        }
        return iv;
    }

    private static byte[] chunkAad(long chunkIndex, int plaintextLength) {
        return ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(chunkIndex)
                .putInt(plaintextLength)
                .array();
    }

    private SecretKey deriveKey(String password, byte[] salt, int keyLength)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (password == null) {
            throw new IllegalArgumentException("Password is required for encrypted files");
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, keyLength);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
        byte[] keyBytes = keyFactory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private InputStream buildDecryptingStream(PushbackInputStream pb, String password) throws Exception {
        byte[] peek = pb.readNBytes(MAGIC_LENGTH);
        boolean isChunkedGcm = peek.length == MAGIC_LENGTH && Arrays.equals(peek, CHUNKED_GCM_MAGIC);
        boolean isMonolithicGcm = peek.length == MAGIC_LENGTH && Arrays.equals(peek, MONOLITHIC_GCM_MAGIC);

        if (isChunkedGcm) {
            byte[] salt = readExact(pb, GCM_SALT_LENGTH, "chunked GCM salt");
            byte[] baseIv = readExact(pb, GCM_IV_LENGTH, "chunked GCM IV");
            int chunkSize = readInt(pb, "chunked GCM chunk size");
            if (chunkSize <= 0 || chunkSize > GCM_CHUNK_SIZE) {
                throw new IOException("Invalid encrypted chunk size: " + chunkSize);
            }
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            return new ChunkedGcmInputStream(pb, key, baseIv, chunkSize);
        }

        if (isMonolithicGcm) {
            byte[] salt = readExact(pb, GCM_SALT_LENGTH, "legacy GCM salt");
            byte[] iv = readExact(pb, GCM_IV_LENGTH, "legacy GCM IV");
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            return new LegacyGcmInputStream(pb, key, iv);
        }

        pb.unread(peek);
        byte[] salt = readExact(pb, LEGACY_CBC_SALT_LENGTH, "legacy CBC salt");
        byte[] iv = readExact(pb, LEGACY_CBC_IV_LENGTH, "legacy CBC IV");
        SecretKey key = deriveKey(password, salt, 128);
        Cipher cipher = Cipher.getInstance(LEGACY_CBC_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        return new CipherInputStream(pb, cipher);
    }

    /**
     * Returns an encrypting stream using the new chunked AES/GCM format.
     */
    public OutputStream getEncryptedOutputStream(OutputStream target, String password) throws Exception {
        try {
            byte[] salt = generateRandomBytes(GCM_SALT_LENGTH);
            byte[] baseIv = generateRandomBytes(GCM_IV_LENGTH);
            target.write(CHUNKED_GCM_MAGIC);
            target.write(salt);
            target.write(baseIv);
            writeInt(target, GCM_CHUNK_SIZE);
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            return new ChunkedGcmOutputStream(target, key, baseIv, GCM_CHUNK_SIZE);
        } catch (Exception e) {
            target.close();
            throw e;
        }
    }

    /**
     * Returns an encrypting stream using the new chunked AES/GCM format.
     */
    public OutputStream getEncryptedOutputStream(File finalFile, String password) throws Exception {
        FileOutputStream fos = new FileOutputStream(finalFile);
        try {
            byte[] salt = generateRandomBytes(GCM_SALT_LENGTH);
            byte[] baseIv = generateRandomBytes(GCM_IV_LENGTH);
            fos.write(CHUNKED_GCM_MAGIC);
            fos.write(salt);
            fos.write(baseIv);
            writeInt(fos, GCM_CHUNK_SIZE);
            SecretKey key = deriveKey(password, salt, KEY_LENGTH);
            return new ChunkedGcmOutputStream(fos, key, baseIv, GCM_CHUNK_SIZE);
        } catch (Exception e) {
            fos.close();
            throw e;
        }
    }

    private static class ChunkedGcmOutputStream extends OutputStream {
        private final OutputStream target;
        private final SecretKey key;
        private final byte[] baseIv;
        private final byte[] plainBuffer;
        private int plainBufferPos;
        private long chunkIndex;
        private boolean closed;

        ChunkedGcmOutputStream(OutputStream target, SecretKey key, byte[] baseIv, int chunkSize) {
            this.target = target;
            this.key = key;
            this.baseIv = baseIv;
            this.plainBuffer = new byte[chunkSize];
        }

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            plainBuffer[plainBufferPos++] = (byte) b;
            if (plainBufferPos == plainBuffer.length) {
                writeEncryptedChunk();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            Objects.checkFromIndexSize(off, len, b.length);
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                int toCopy = Math.min(remaining, plainBuffer.length - plainBufferPos);
                System.arraycopy(b, offset, plainBuffer, plainBufferPos, toCopy);
                plainBufferPos += toCopy;
                offset += toCopy;
                remaining -= toCopy;
                if (plainBufferPos == plainBuffer.length) {
                    writeEncryptedChunk();
                }
            }
        }

        @Override
        public void flush() throws IOException {
            ensureOpen();
            target.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                if (plainBufferPos > 0) {
                    writeEncryptedChunk();
                }
                target.flush();
            } catch (IOException e) {
                failure = e;
                throw e;
            } finally {
                try {
                    target.close();
                } catch (IOException e) {
                    if (failure != null) {
                        failure.addSuppressed(e);
                    } else {
                        throw e;
                    }
                }
            }
        }

        private void writeEncryptedChunk() throws IOException {
            try {
                int plaintextLength = plainBufferPos;
                Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, chunkIv(baseIv, chunkIndex)));
                cipher.updateAAD(chunkAad(chunkIndex, plaintextLength));
                byte[] encrypted = cipher.doFinal(plainBuffer, 0, plaintextLength);
                writeInt(target, encrypted.length);
                target.write(encrypted);
                plainBufferPos = 0;
                chunkIndex++;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to encrypt file chunk", e);
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Encrypted stream is already closed");
            }
        }
    }

    private static class ChunkedGcmInputStream extends InputStream {
        private final InputStream source;
        private final SecretKey key;
        private final byte[] baseIv;
        private final int chunkSize;
        private byte[] plainBuffer = new byte[0];
        private int plainBufferPos;
        private long chunkIndex;
        private boolean eof;
        private boolean closed;

        ChunkedGcmInputStream(InputStream source, SecretKey key, byte[] baseIv, int chunkSize) {
            this.source = source;
            this.key = key;
            this.baseIv = baseIv;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (!ensurePlainBytes()) {
                return -1;
            }
            int toCopy = Math.min(len, plainBuffer.length - plainBufferPos);
            System.arraycopy(plainBuffer, plainBufferPos, b, off, toCopy);
            plainBufferPos += toCopy;
            return toCopy;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            source.close();
        }

        private boolean ensurePlainBytes() throws IOException {
            while (plainBufferPos >= plainBuffer.length) {
                if (eof) {
                    return false;
                }
                readEncryptedChunk();
            }
            return true;
        }

        private void readEncryptedChunk() throws IOException {
            Integer encryptedLength = readNullableInt();
            if (encryptedLength == null) {
                eof = true;
                plainBuffer = new byte[0];
                plainBufferPos = 0;
                return;
            }
            if (encryptedLength < GCM_TAG_BYTES || encryptedLength > chunkSize + GCM_TAG_BYTES) {
                throw new IOException("Invalid encrypted chunk length: " + encryptedLength);
            }
            byte[] encrypted = readExact(source, encryptedLength, "encrypted chunk");
            try {
                int plaintextLength = encryptedLength - GCM_TAG_BYTES;
                Cipher cipher = Cipher.getInstance(GCM_ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, chunkIv(baseIv, chunkIndex)));
                cipher.updateAAD(chunkAad(chunkIndex, plaintextLength));
                plainBuffer = cipher.doFinal(encrypted);
                plainBufferPos = 0;
                chunkIndex++;
            } catch (AEADBadTagException e) {
                throw new IOException("Encrypted chunk authentication failed", e);
            } catch (Exception e) {
                throw new IOException("Failed to decrypt encrypted chunk", e);
            }
        }

        private Integer readNullableInt() throws IOException {
            byte[] data = new byte[Integer.BYTES];
            int offset = 0;
            while (offset < Integer.BYTES) {
                int read = source.read(data, offset, Integer.BYTES - offset);
                if (read == -1) {
                    if (offset == 0) {
                        return null;
                    }
                    throw new EOFException("Unexpected end of encrypted stream while reading chunk length");
                }
                offset += read;
            }
            return ByteBuffer.wrap(data).getInt();
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Encrypted stream is already closed");
            }
        }
    }

    private static class LegacyGcmInputStream extends InputStream {
        private static final int READ_BUFFER_SIZE = 256 * 1024;

        private final InputStream source;
        private final GCMBlockCipher cipher;
        private final byte[] readBuffer = new byte[READ_BUFFER_SIZE];
        private byte[] plainBuffer = new byte[0];
        private int plainBufferPos;
        private boolean eof;
        private boolean closed;

        @SuppressWarnings("deprecation")
        LegacyGcmInputStream(InputStream source, SecretKey key, byte[] iv) {
            this.source = source;
            this.cipher = new GCMBlockCipher(new AESEngine());
            this.cipher.init(false, new AEADParameters(new KeyParameter(key.getEncoded()), GCM_TAG_BITS, iv));
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (!ensurePlainBytes()) {
                return -1;
            }
            int toCopy = Math.min(len, plainBuffer.length - plainBufferPos);
            System.arraycopy(plainBuffer, plainBufferPos, b, off, toCopy);
            plainBufferPos += toCopy;
            return toCopy;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            source.close();
        }

        private boolean ensurePlainBytes() throws IOException {
            while (plainBufferPos >= plainBuffer.length) {
                if (eof) {
                    return false;
                }
                refill();
            }
            return true;
        }

        private void refill() throws IOException {
            int read = source.read(readBuffer);
            if (read == -1) {
                eof = true;
                byte[] finalBuffer = new byte[cipher.getOutputSize(0)];
                try {
                    int finalLength = cipher.doFinal(finalBuffer, 0);
                    plainBuffer = finalLength == finalBuffer.length
                            ? finalBuffer
                            : Arrays.copyOf(finalBuffer, finalLength);
                    plainBufferPos = 0;
                } catch (InvalidCipherTextException e) {
                    throw new IOException("Legacy GCM authentication failed", e);
                }
                return;
            }

            byte[] output = new byte[cipher.getUpdateOutputSize(read)];
            int outputLength = cipher.processBytes(readBuffer, 0, read, output, 0);
            if (outputLength == 0) {
                refill();
                return;
            }
            plainBuffer = outputLength == output.length ? output : Arrays.copyOf(output, outputLength);
            plainBufferPos = 0;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Encrypted stream is already closed");
            }
        }
    }
}
