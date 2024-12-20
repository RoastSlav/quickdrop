package org.rostislav.quickdrop.model;

public class FileSession {
    private String password;
    private String fileUuid;

    public FileSession() {
    }

    public FileSession(String password, String fileUuid) {
        this.password = password;
        this.fileUuid = fileUuid;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFileUuid() {
        return fileUuid;
    }

    public void setFileUuid(String fileUuid) {
        this.fileUuid = fileUuid;
    }
}
