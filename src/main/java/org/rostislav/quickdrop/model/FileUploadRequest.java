package org.rostislav.quickdrop.model;

public class FileUploadRequest {
    public String fileName;
    public int totalChunks;
    public Long fileSize;
    public String description;
    public boolean keepIndefinitely;
    public String password;
    public boolean hidden;
    public String uploaderIp;
    public String uploaderUserAgent;
    public boolean folderUpload;
    public String folderName;
    public String folderManifest;

    public FileUploadRequest() {
    }

    public FileUploadRequest(String description, boolean keepIndefinitely, String password, boolean hidden, String fileName, int totalChunks, Long fileSize, String uploaderIp, String uploaderUserAgent, boolean folderUpload, String folderName, String folderManifest) {
        this.description = description;
        this.keepIndefinitely = keepIndefinitely;
        this.password = password;
        this.hidden = hidden;
        this.fileName = fileName;
        this.totalChunks = totalChunks;
        this.fileSize = fileSize;
        this.uploaderIp = uploaderIp;
        this.uploaderUserAgent = uploaderUserAgent;
        this.folderUpload = folderUpload;
        this.folderName = folderName;
        this.folderManifest = folderManifest;
    }
}
