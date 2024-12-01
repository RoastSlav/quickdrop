package org.rostislav.quickdrop.model;

import org.rostislav.quickdrop.entity.FileEntity;

import java.time.LocalDate;

public class FileEntityView {
    public Long id;
    public String name;
    public String uuid;
    public String description;
    public String size;
    public boolean keepIndefinitely;
    public LocalDate uploadDate;
    public long totalDownloads;

    public FileEntityView() {
    }

    public FileEntityView(FileEntity fileEntity, String formatedSize, long totalDownloads) {
        this.id = fileEntity.id;
        this.name = fileEntity.name;
        this.uuid = fileEntity.uuid;
        this.description = fileEntity.description;
        this.size = formatedSize;
        this.keepIndefinitely = fileEntity.keepIndefinitely;
        this.uploadDate = fileEntity.uploadDate;
        this.totalDownloads = totalDownloads;
    }
}
