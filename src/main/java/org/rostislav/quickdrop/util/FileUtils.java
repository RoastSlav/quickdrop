package org.rostislav.quickdrop.util;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;

public class FileUtils {
    public static String formatFileSize(long size) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double sizeInUnits = size;
        while (sizeInUnits >= 1024 && unitIndex < units.length - 1) {
            sizeInUnits /= 1024;
            unitIndex++;
        }
        return String.format("%.2f %s", sizeInUnits, units[unitIndex]);
    }

    public static String getDownloadLink(HttpServletRequest request, FileEntity fileEntity) {
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/file/" + fileEntity.uuid;
    }
}
