package org.rostislav.quickdrop.model;

public class FileUploadRequest {
    public String description;
    public boolean keepIndefinitely;
    public String password;
    public boolean hidden;

    public FileUploadRequest() {
    }

    public FileUploadRequest(String description, boolean keepIndefinitely, String password, boolean hidden) {
        this.description = description;
        this.keepIndefinitely = keepIndefinitely;
        this.password = password;
        this.hidden = hidden;
    }
}
