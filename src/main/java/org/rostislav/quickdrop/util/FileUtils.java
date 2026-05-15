package org.rostislav.quickdrop.util;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.rostislav.quickdrop.service.FileService.logger;

/**
 * Static utility methods for file handling, preview type detection, share token
 * validation, streaming, and formatting.
 *
 * <p>Non-instantiable utility class following the static-factory pattern.
 */
public class FileUtils {

    /**
     * Extensions treated as plain-text and eligible for the in-browser text preview.
     */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".log", ".md", ".json", ".jsonl", ".yaml", ".yml", ".csv", ".tsv", ".xml",
            ".c", ".cpp", ".cxx", ".h", ".hpp",
            ".java", ".js", ".jsx", ".ts", ".tsx",
            ".py", ".rb", ".go", ".rs", ".cs",
            ".php", ".sh", ".bash", ".zsh", ".css",
            ".html", ".htm", ".sql"
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg"
    );

    private static final Set<String> PDF_EXTENSIONS = Set.of(".pdf");
    private static final Set<String> JSON_EXTENSIONS = Set.of(".json", ".jsonl");
    private static final Set<String> CSV_TSV_EXTENSIONS = Set.of(".csv", ".tsv");

    private FileUtils() {
        // Prevent instantiation
    }

    /**
     * Wraps an {@link InputStream} in a {@link StreamingResponseBody} that copies
     * all bytes to the response output stream in 8 KB chunks.
     *
     * @param inputStream source stream
     * @return a streaming response body
     */
    public static StreamingResponseBody getStreamingResponseBody(InputStream inputStream) {
        return outputStream -> {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        };
    }

    /**
     * Resolves the real client IP address from the request, preferring the
     * {@code X-Forwarded-For} header (first entry) over {@code X-Real-IP}, then
     * {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}.
     *
     * @param request the current HTTP request
     * @return a record containing the resolved IP and User-Agent string
     */
    public static FileService.RequesterInfo getRequesterInfo(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        String ipAddress;

        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            ipAddress = forwardedFor.split(",")[0].trim();
        } else if (realIp != null && !realIp.isEmpty()) {
            ipAddress = realIp;
        } else {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return new FileService.RequesterInfo(ipAddress, userAgent);
    }

    /**
     * Formats a raw byte count as a human-readable size string with two decimal places
     * (e.g. {@code 1.50 MB}).
     *
     * @param size size in bytes
     * @return formatted size string
     */
    public static String formatFileSize(long size) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double sizeInUnits = size;

        while (sizeInUnits >= 1024 && unitIndex < units.length - 1) {
            sizeInUnits /= 1024.0;
            unitIndex++;
        }

        return String.format("%.2f %s", sizeInUnits, units[unitIndex]);
    }

    /**
     * Builds the absolute download URL for a file, preferring the {@code X-Forwarded-Proto}
     * header for the scheme.
     *
     * @param request    the current HTTP request (used for scheme and server name)
     * @param fileEntity the file whose download link should be generated
     * @return absolute URL string (e.g. {@code https://example.com/file/abc-123})
     */
    public static String getDownloadLink(HttpServletRequest request, FileEntity fileEntity) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) {
            scheme = request.getScheme();
        }
        return scheme + "://" + request.getServerName() + "/file/" + fileEntity.uuid;
    }

    /**
     * Returns the URL path for a share token (e.g. {@code /share/abc12}).
     *
     * @param token the share token string, or {@code null}
     * @return the share path, or {@code ""} if the token is null
     */
    public static String getSharePath(String token) {
        if (token == null) {
            return "";
        }
        return "/share/" + token;
    }

    /**
     * Converts bytes to mebibytes (integer division).
     *
     * @param bytes value in bytes
     * @return value in mebibytes
     */
    public static long bytesToMegabytes(long bytes) {
        return bytes / 1024 / 1024;
    }

    /**
     * Converts mebibytes to bytes.
     *
     * @param megabytes value in mebibytes
     * @return value in bytes
     */
    public static long megabytesToBytes(long megabytes) {
        return megabytes * 1024 * 1024;
    }

    private static String lowerName(FileEntity fileEntity) {
        return fileEntity == null || fileEntity.name == null ? "" : fileEntity.name.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the canonical preview type string for use in Thymeleaf templates.
     *
     * @param isImage {@code true} if the file is a previewable image
     * @param isPdf   {@code true} if the file is a PDF
     * @param isJson  {@code true} if the file is JSON
     * @param isCsv   {@code true} if the file is CSV/TSV
     * @param isText  {@code true} if the file is a plain-text type
     * @return one of {@code "image"}, {@code "pdf"}, {@code "json"}, {@code "csv"}, {@code "text"}, or {@code ""}
     */
    public static String determinePreviewType(boolean isImage, boolean isPdf, boolean isJson, boolean isCsv, boolean isText) {
        if (isImage) return "image";
        if (isPdf) return "pdf";
        if (isJson) return "json";
        if (isCsv) return "csv";
        if (isText) return "text";
        return "";
    }

    /** Returns {@code true} when the file's extension indicates plain-text content. */
    public static boolean isPreviewableText(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Returns {@code true} when the file's extension indicates an image format. */
    public static boolean isPreviewableImage(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Returns {@code true} when the file's extension indicates a PDF document. */
    public static boolean isPreviewablePdf(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return PDF_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Returns {@code true} when the file's extension indicates JSON content. */
    public static boolean isPreviewableJson(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return JSON_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Returns {@code true} when the file's extension indicates CSV or TSV content. */
    public static boolean isPreviewableCsvOrTsv(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return CSV_TSV_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * Returns a MIME type string for the given file, suitable for the {@code Content-Type}
     * response header when serving an inline preview.
     *
     * <p>SVG files return {@code image/png}. All other image types without a specific
     * match return {@code image/jpeg}.
     *
     * @param fileName the original filename, used to distinguish image subtypes
     * @param isImage  whether the file is a previewable image
     * @param isText   whether the file is plain text
     * @param isPdf    whether the file is a PDF
     * @return MIME type string
     */
    public static String guessContentType(String fileName, boolean isImage, boolean isText, boolean isPdf) {
        if (isImage) {
            if (fileName.toLowerCase().endsWith(".webp")) return "image/webp";
            if (fileName.toLowerCase().endsWith(".gif")) return "image/gif";
            if (fileName.toLowerCase().endsWith(".png")) return "image/png";
            if (fileName.toLowerCase().endsWith(".svg")) return "image/png";
            return "image/jpeg";
        }
        if (isPdf) {
            return "application/pdf";
        }
        if (isText) {
            return "text/plain; charset=UTF-8";
        }
        return "application/octet-stream";
    }

    /**
     * Generates a short (5-character) base-62 share token seeded from the file's
     * UUID, size, upload date, and additional randomness.
     *
     * <p>The seed is SHA-256 hashed and truncated to 5 characters from the base-62
     * encoding of the digest. Uniqueness is not guaranteed.
     *
     * @param fileEntity the file to generate a token for
     * @return a 5-character base-62 token string
     */
    public static String generateHashedToken(FileEntity fileEntity) {
        String seed = String.join(":",
                fileEntity.uuid,
                String.valueOf(fileEntity.size),
                String.valueOf(fileEntity.uploadDate),
                String.valueOf(System.nanoTime()),
                String.valueOf(ThreadLocalRandom.current().nextLong())
        );

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            String base62 = toBase62(digest);
            return base62.length() >= 5 ? base62.substring(0, 5) : String.format("%1$-5s", base62).replace(' ', '0');
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Encodes a byte array as a base-62 string ({@code 0-9A-Za-z}).
     *
     * @param bytes the bytes to encode
     * @return base-62 string representation
     */
    public static String toBase62(byte[] bytes) {
        final String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        BigInteger value = new BigInteger(1, bytes);
        if (value.equals(BigInteger.ZERO)) {
            return alphabet.substring(0, 1);
        }

        StringBuilder builder = new StringBuilder();
        BigInteger base = BigInteger.valueOf(alphabet.length());

        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = value.divideAndRemainder(base);
            builder.append(alphabet.charAt(divRem[1].intValue()));
            value = divRem[0];
        }

        return builder.reverse().toString();
    }

    /**
     * Returns {@code true} if the share token has not expired and has remaining downloads.
     * A token whose {@code tokenExpirationDate} equals today is considered expired.
     *
     * @param token the token to validate (may be {@code null})
     * @return {@code true} when the token is usable
     */
    public static boolean validateShareToken(ShareTokenEntity token) {
        if (token == null) {
            return false;
        }

        boolean notExpired = token.tokenExpirationDate == null || LocalDate.now().isBefore(token.tokenExpirationDate);
        boolean hasDownloads = token.numberOfAllowedDownloads == null || token.numberOfAllowedDownloads > 0;
        return notExpired && hasDownloads;
    }

    /**
     * Streams a file to the output stream.
     *
     * @param filePathToStream  path of the file to read
     * @param decryptedFilePath unused
     * @param uuid              file UUID (used in log messages only)
     * @param outputStream      destination output stream
     * @throws IOException if streaming fails
     */
    public static void streamFile(Path filePathToStream, Path decryptedFilePath, String uuid, OutputStream outputStream) throws IOException {
        try (InputStream in = Files.newInputStream(filePathToStream)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } catch (IOException e) {
            logger.error("Error streaming file for UUID: {}", uuid, e);
            throw e;
        }
    }
}
