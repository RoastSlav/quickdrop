package org.rostislav.quickdrop.util;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.springframework.ui.Model;

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

    public static String getShareLink(HttpServletRequest request, FileEntity fileEntity, String token) {
        return request.getScheme() + "://" + request.getServerName() + "/file/share/" + fileEntity.uuid + "/" + token;
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
}
