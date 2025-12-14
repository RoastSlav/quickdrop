package org.rostislav.quickdrop.util;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.http.HttpHeaders;
import org.springframework.ui.Model;
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

public class FileUtils {
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
        // To prevent instantiation
    }

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

    public static FileService.RequesterInfo getRequesterInfo(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String realIp = request.getHeader("X-Real-IP");
        String ipAddress;

        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // The X-Forwarded-For header can contain multiple IPs, pick the first one
            ipAddress = forwardedFor.split(",")[0].trim();
        } else if (realIp != null && !realIp.isEmpty()) {
            ipAddress = realIp;
        } else {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return new FileService.RequesterInfo(ipAddress, userAgent);
    }

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

    public static String getDownloadLink(HttpServletRequest request, FileEntity fileEntity) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) {
            scheme = request.getScheme(); // Fallback to the default scheme
        }
        return scheme + "://" + request.getServerName() + "/file/" + fileEntity.uuid;
    }

    public static String getShareLink(HttpServletRequest request, String token) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) {
            scheme = request.getScheme();
        }
        return scheme + "://" + request.getServerName() + "/share/" + token;
    }

    public static long bytesToMegabytes(long bytes) {
        return bytes / 1024 / 1024;
    }

    public static long megabytesToBytes(long megabytes) {
        return megabytes * 1024 * 1024;
    }

    public static void populateModelAttributes(FileEntity fileEntity, Model model, HttpServletRequest request) {
        model.addAttribute("file", fileEntity);
        model.addAttribute("fileSize", formatFileSize(fileEntity.size));
        model.addAttribute("downloadLink", getDownloadLink(request, fileEntity));
    }

    private static String lowerName(FileEntity fileEntity) {
        return fileEntity == null || fileEntity.name == null ? "" : fileEntity.name.toLowerCase(Locale.ROOT);
    }

    public static boolean isPreviewableText(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static boolean isPreviewableImage(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static boolean isPreviewablePdf(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return PDF_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static boolean isPreviewableJson(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return JSON_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static boolean isPreviewableCsvOrTsv(FileEntity fileEntity) {
        String lower = lowerName(fileEntity);
        return CSV_TSV_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public static String guessContentType(String fileName, boolean isImage, boolean isText, boolean isPdf) {
        if (isImage) {
            if (fileName.toLowerCase().endsWith(".svg")) return "image/svg+xml";
            if (fileName.toLowerCase().endsWith(".webp")) return "image/webp";
            if (fileName.toLowerCase().endsWith(".gif")) return "image/gif";
            if (fileName.toLowerCase().endsWith(".png")) return "image/png";
            return "image/jpeg";
        }
        if (isPdf) {
            return "application/pdf";
        }
        if (isText) {
            if (fileName.toLowerCase().endsWith(".json")) return "application/json";
            if (fileName.toLowerCase().endsWith(".xml")) return "application/xml";
            if (fileName.toLowerCase().endsWith(".csv")) return "text/csv";
            if (fileName.toLowerCase().endsWith(".tsv")) return "text/tab-separated-values";
            if (fileName.toLowerCase().endsWith(".md")) return "text/markdown";
            return "text/plain; charset=UTF-8";
        }
        return "application/octet-stream";
    }

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

    public static boolean validateShareToken(ShareTokenEntity token) {
        if (token == null) {
            return false;
        }

        boolean notExpired = token.tokenExpirationDate == null || !LocalDate.now().isAfter(token.tokenExpirationDate);
        boolean hasDownloads = token.numberOfAllowedDownloads == null || token.numberOfAllowedDownloads > 0;
        return notExpired && hasDownloads;
    }

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
        } finally {
            // If there's a decrypted file, remove it after streaming
            if (filePathToStream != null && filePathToStream.equals(decryptedFilePath)) {
                try {
                    Files.deleteIfExists(decryptedFilePath);
                    logger.info("Deleted decrypted file after download: {}", decryptedFilePath);
                } catch (IOException e) {
                    logger.error("Failed to delete decrypted file: {}", decryptedFilePath, e);
                }
            }
        }
    }
}
