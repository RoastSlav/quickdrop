package org.rostislav.quickdrop;

import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;

import java.util.UUID;

public class TestDataContainer {

    public static FileEntity getFileEntity() {
        FileEntity fileEntity = new FileEntity();
        fileEntity.name = "test.txt";
        fileEntity.uuid = UUID.randomUUID().toString();
        fileEntity.description = "Test description";
        fileEntity.size = 1024L;
        fileEntity.keepIndefinitely = false;
        fileEntity.passwordHash = "hashed-password";
        return fileEntity;
    }

    public static FileEntity getEmptyFileEntity() {
        return new FileEntity();
    }

    public static FileUploadRequest getFileUploadRequest() {
        return new FileUploadRequest("Test description", false, "password123");
    }

    public static FileUploadRequest getEmptyFileUploadRequest() {
        return new FileUploadRequest();
    }
}
