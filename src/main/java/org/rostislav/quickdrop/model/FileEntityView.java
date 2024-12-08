package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.FileEntity;

import java.time.LocalDate;

import static org.rostislav.quickdrop.util.FileUtils.formatFileSize;

public class FileEntityView {
    public Long id;
    public String name;
    public String uuid;
    public String description;
    public String size;
    public boolean keepIndefinitely;
    public LocalDate uploadDate;
    public long totalDownloads;
    public boolean hidden;

    public FileEntityView() {
    }

    public FileEntityView(FileEntity fileEntity, long totalDownloads) {
        this.id = fileEntity.id;
        this.name = fileEntity.name;
        this.uuid = fileEntity.uuid;
        this.description = fileEntity.description;
        this.size = formatFileSize(fileEntity.size);
        this.keepIndefinitely = fileEntity.keepIndefinitely;
        this.uploadDate = fileEntity.uploadDate;
        this.totalDownloads = totalDownloads;
        this.hidden = fileEntity.hidden;
    }
}
