package org.rostislav.quickdrop.model;

/**
 * In-memory session record that binds a file-access token to the cleartext password
 * and the UUID of the protected file.
 *
 * <p>Stored in {@link org.rostislav.quickdrop.service.SessionService}'s in-memory map
 * and looked up whenever a download or preview of an encrypted file is requested.
 * The cleartext password is kept in memory so that the file can be decrypted
 * on-the-fly without prompting the user again.
 */
public class FileSession {
    /**
     * Cleartext access password for the file (used to derive the AES decryption key).
     */
    private String password;

    /** UUID of the file this session grants access to. */
    private String fileUuid;

    public FileSession() {
    }

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
