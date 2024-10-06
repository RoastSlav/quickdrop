package org.rostislav.quickdrop.util;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;
import org.springframework.ui.Model;

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

    public static void populateModelAttributes(FileEntity fileEntity, Model model, HttpServletRequest request) {
        model.addAttribute("file", fileEntity);
        model.addAttribute("fileSize", FileUtils.formatFileSize(fileEntity.size));
        model.addAttribute("downloadLink", getDownloadLink(request, fileEntity));
    }
}
