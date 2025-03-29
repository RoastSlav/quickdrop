package org.rostislav.quickdrop.model;

public class FileUploadRequest {
    public String fileName;
    public int totalChunks;
    public Long fileSize;
    public String description;
    public boolean keepIndefinitely;
    public String password;
    public boolean hidden;

    public FileUploadRequest() {
    }

    public FileUploadRequest(String description, boolean keepIndefinitely, String password, boolean hidden, String fileName, int totalChunks, Long fileSize) {
        this.description = description;
        this.keepIndefinitely = keepIndefinitely;
        this.password = password;
        this.hidden = hidden;
        this.fileName = fileName;
        this.totalChunks = totalChunks;
        this.fileSize = fileSize;
    }
}
