package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * Represents a single uploaded file or text paste stored by the application.
 *
 * <p>Files and pastes share this entity — {@link #paste} distinguishes between them.
 * The physical file is stored on disk under the configured storage path using
 * {@link #uuid} as the filename. All fields are public for direct field access
 * in service/repository layer code; there are no accessor methods.
 */
@Entity
public class FileEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * Original filename as provided by the uploader, or the paste title with extension.
     */
    public String name;

    /** Unique identifier used as the on-disk filename and as the URL path segment. */
    public String uuid;

    /** Optional human-readable description provided at upload time. */
    public String description;

    /** File size in bytes. For pastes, this is the UTF-8 byte length of the content. */
    public long size;

    /** When {@code true} the file is exempt from the scheduled age-based deletion. */
    public boolean keepIndefinitely;

    /** Date the file was first persisted; set automatically by {@link #prePersist()}. */
    public LocalDate uploadDate;

    /** BCrypt hash of the access password, or {@code null} if the file is not password-protected. */
    public String passwordHash;

    /** When {@code true} the file is not shown on the public /file/list page. */
    public boolean hidden;

    /** When {@code true} the file contents are AES-encrypted on disk. */
    public boolean encrypted;

    /** Distinguishes paste entries ({@code true}) from regular binary/text uploads ({@code false}). */
    @Column(name = "is_paste")
    public boolean paste;

    /** Whether this entry represents a whole folder upload (ZIP archive with manifest). */
    public boolean folderUpload;

    /** Display name of the uploaded folder. */
    public String folderName;

    /** JSON array describing the folder's file tree, stored as TEXT to avoid length limits. */
    @Column(columnDefinition = "TEXT")
    public String folderManifest;

    /** Sets {@link #uploadDate} to today before the first database INSERT. */
    @PrePersist
    public void prePersist() {
        uploadDate = LocalDate.now();
    }

    @Override
    public String toString() {
        return "FileEntity{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", uuid='" + uuid + '\'' +
                ", description='" + description + '\'' +
                ", size=" + size +
                ", keepIndefinitely=" + keepIndefinitely +
                ", uploadDate=" + uploadDate +
                ", hidden=" + hidden +
                ", encrypted=" + encrypted +
                ", paste=" + paste +
                ", folderUpload=" + folderUpload +
                ", folderName='" + folderName + '\'' +
                '}';
    }
}
