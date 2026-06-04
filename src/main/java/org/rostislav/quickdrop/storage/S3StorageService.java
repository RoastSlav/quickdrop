package org.rostislav.quickdrop.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link StorageService} implementation backed by S3 or any S3-compatible object store
 * (MinIO, Cloudflare R2, DigitalOcean Spaces, Backblaze B2, etc.).
 *
 * <p>Writes use a buffered multipart upload strategy: data is accumulated in an 8 MB
 * in-memory buffer; each full buffer is uploaded as one S3 part. On stream close the
 * final (possibly smaller) part is flushed and the multipart upload is completed. If
 * the stream is closed after an exception, the in-progress multipart upload is aborted.
 *
 * <p>The S3 client is created lazily and recreated via {@link #refreshClient()} whenever
 * connection settings change in the admin panel.
 */
public class S3StorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(S3StorageService.class);
    private static final int PART_SIZE = 8 * 1024 * 1024; // 8 MB — above S3's 5 MB minimum

    private final java.util.function.Supplier<S3Config> configSupplier;
    private volatile S3Client client;

    public S3StorageService(java.util.function.Supplier<S3Config> configSupplier) {
        this.configSupplier = configSupplier;
    }

    /** Rebuilds the S3 client from the current settings. Call after admin saves new S3 config. */
    public synchronized void refreshClient() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
        client = buildClient(configSupplier.get());
        logger.info("S3 client refreshed");
    }

    private S3Client getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = buildClient(configSupplier.get());
                }
            }
        }
        return client;
    }

    private S3Client buildClient(S3Config cfg) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(cfg.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())));
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.endpoint()));
        }
        if (cfg.pathStyle()) {
            builder.forcePathStyle(true);
        }
        return builder.build();
    }

    private String objectKey(String key) {
        S3Config cfg = configSupplier.get();
        String prefix = cfg.prefix();
        if (prefix == null || prefix.isBlank()) return key;
        return prefix.endsWith("/") ? prefix + key : prefix + "/" + key;
    }

    private String bucket() {
        return configSupplier.get().bucket();
    }

    @Override
    public InputStream getInputStream(String key) throws IOException {
        try {
            return getClient().getObject(GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey(key))
                    .build());
        } catch (S3Exception e) {
            throw new IOException("Failed to open S3 object: " + key, e);
        }
    }

    @Override
    public OutputStream getOutputStream(String key) throws IOException {
        String fullKey = objectKey(key);
        try {
            String uploadId = getClient().createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket())
                    .key(fullKey)
                    .build()).uploadId();
            return new S3MultipartOutputStream(fullKey, uploadId);
        } catch (S3Exception e) {
            throw new IOException("Failed to start S3 multipart upload for: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            getClient().headObject(HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey(key))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            logger.warn("S3 exists check failed for key {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            getClient().deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(objectKey(key))
                    .build());
            logger.info("Deleted S3 object: {}", key);
            return true;
        } catch (S3Exception e) {
            logger.error("Failed to delete S3 object {}: {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listKeySuffix(String suffix) {
        try {
            String prefix = configSupplier.get().prefix();
            ListObjectsV2Request req = ListObjectsV2Request.builder()
                    .bucket(bucket())
                    .prefix(prefix == null ? "" : prefix)
                    .build();
            List<String> result = new ArrayList<>();
            ListObjectsV2Response response;
            do {
                response = getClient().listObjectsV2(req);
                for (S3Object obj : response.contents()) {
                    String name = obj.key();
                    // strip prefix to get the bare key
                    if (prefix != null && !prefix.isBlank()) {
                        name = name.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")
                                ? name.substring(prefix.length() + (prefix.endsWith("/") ? 0 : 1))
                                : name;
                    }
                    if (name.endsWith(suffix)) {
                        result.add(name);
                    }
                }
                req = req.toBuilder().continuationToken(response.nextContinuationToken()).build();
            } while (Boolean.TRUE.equals(response.isTruncated()));
            return result;
        } catch (S3Exception e) {
            logger.error("Failed to list S3 objects with suffix {}: {}", suffix, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public StorageBackend getBackend() {
        return StorageBackend.S3;
    }

    /**
     * Tests the S3 connection by calling HeadBucket.
     *
     * @return {@code null} on success; an error message string on failure
     */
    public String testConnection() {
        try {
            refreshClient();
            getClient().headBucket(HeadBucketRequest.builder().bucket(bucket()).build());
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Buffered output stream that uploads to S3 via multipart upload on close. */
    private class S3MultipartOutputStream extends OutputStream {
        private final String key;
        private final String uploadId;
        private final List<CompletedPart> completedParts = new ArrayList<>();
        private int partNumber = 1;
        private final byte[] buffer = new byte[PART_SIZE];
        private int bufferPos = 0;
        private boolean closed = false;
        private boolean aborted = false;

        S3MultipartOutputStream(String key, String uploadId) {
            this.key = key;
            this.uploadId = uploadId;
        }

        @Override
        public void write(int b) throws IOException {
            checkNotClosed();
            buffer[bufferPos++] = (byte) b;
            if (bufferPos == PART_SIZE) flushBuffer();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkNotClosed();
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                int toCopy = Math.min(remaining, PART_SIZE - bufferPos);
                System.arraycopy(b, offset, buffer, bufferPos, toCopy);
                bufferPos += toCopy;
                offset += toCopy;
                remaining -= toCopy;
                if (bufferPos == PART_SIZE) flushBuffer();
            }
        }

        private void flushBuffer() throws IOException {
            if (bufferPos == 0) return;
            byte[] data = new byte[bufferPos];
            System.arraycopy(buffer, 0, data, 0, bufferPos);
            try {
                UploadPartResponse response = getClient().uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket())
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) data.length)
                                .build(),
                        RequestBody.fromBytes(data));
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(response.eTag())
                        .build());
                partNumber++;
                bufferPos = 0;
            } catch (S3Exception e) {
                abort();
                throw new IOException("S3 uploadPart failed for key: " + key, e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                flushBuffer();
                getClient().completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                        .bucket(bucket())
                        .key(key)
                        .uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(completedParts)
                                .build())
                        .build());
                logger.info("S3 multipart upload completed: {}", key);
            } catch (Exception e) {
                abort();
                throw new IOException("Failed to complete S3 multipart upload for: " + key, e);
            }
        }

        private void abort() {
            if (aborted) return;
            aborted = true;
            try {
                getClient().abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket())
                        .key(key)
                        .uploadId(uploadId)
                        .build());
                logger.warn("Aborted S3 multipart upload: {}", key);
            } catch (Exception e) {
                logger.error("Failed to abort S3 multipart upload {}: {}", key, e.getMessage());
            }
        }

        private void checkNotClosed() throws IOException {
            if (closed) throw new IOException("Stream already closed for key: " + key);
        }
    }

    /** Immutable snapshot of S3 connection settings. */
    public record S3Config(
            String endpoint,
            String bucket,
            String region,
            String accessKey,
            String secretKey,
            boolean pathStyle,
            String prefix
    ) {}
}
