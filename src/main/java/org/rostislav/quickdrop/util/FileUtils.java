package org.rostislav.quickdrop.util;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.springframework.ui.Model;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import static org.rostislav.quickdrop.service.FileService.logger;

public class FileUtils {
    private FileUtils() {
        // To prevent instantiation
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

    public static boolean isPreviewableText(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") || lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".xml")
                || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".cxx") || lower.endsWith(".h") || lower.endsWith(".hpp")
                || lower.endsWith(".java") || lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".py") || lower.endsWith(".rb") || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".cs")
                || lower.endsWith(".php") || lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh") || lower.endsWith(".css")
                || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".sql");
    }

    public static boolean isPreviewableImage(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".svg");
    }

    public static boolean isPreviewablePdf(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        return fileEntity.name.toLowerCase().endsWith(".pdf");
    }

    public static boolean isPreviewableJson(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".json") || lower.endsWith(".jsonl");
    }

    public static boolean isPreviewableCsvOrTsv(FileEntity fileEntity) {
        if (fileEntity == null || fileEntity.name == null) return false;
        String lower = fileEntity.name.toLowerCase();
        return lower.endsWith(".csv") || lower.endsWith(".tsv");
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
        try (FileInputStream inputStream = new FileInputStream(filePathToStream.toFile())) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } catch (Exception e) {
            logger.error("Error streaming file for UUID: {}", uuid, e);
            throw e;
        } finally {
            // If there's a decrypted file, remove it after streaming
            if (filePathToStream.equals(decryptedFilePath)) {
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
