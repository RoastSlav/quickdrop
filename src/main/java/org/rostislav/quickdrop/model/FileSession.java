package org.rostislav.quickdrop.model;

/**
 * In-memory session record that binds a file-access token to the cleartext password
 * and the UUID of the protected file.
 */
public class FileSession {
    /**
     * Cleartext access password for the file.
     */
    private final String password;

    /**
     * UUID of the file this session grants access to.
     */
    private final String fileUuid;

    /**
     * @param password cleartext file access password
     * @param fileUuid UUID of the protected file
     */
    public FileSession(String password, String fileUuid) {
        this.password = password;
        this.fileUuid = fileUuid;
    }

    public String getPassword() {
        return password;
    }

    public String getFileUuid() {
        return fileUuid;
    }
}
