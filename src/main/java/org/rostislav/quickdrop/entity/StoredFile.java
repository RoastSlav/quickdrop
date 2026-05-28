package org.rostislav.quickdrop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Represents a binary or text file upload stored by the application.
 *
 * <p>Extends {@link Upload} with folder-upload fields.  Maps to the {@code file}
 * table in a JPA {@code JOINED} inheritance hierarchy; the common fields live in
 * the {@code upload} base table.
 *
 * <p>A discriminator value of {@code "0"} in the {@code upload.paste} column
 * identifies rows belonging to this subtype.
 *
 * <p>Folder uploads are ZIP archives accompanied by a JSON manifest that describes
 * the original directory tree.  When {@link #folderUpload} is {@code false} the
 * folder fields are {@code null} / {@code false}.
 *
 * <p>All fields are {@code public} for direct access; there are no getter/setter
 * accessor methods.
 */
@Entity
@Table(name = "file")
@DiscriminatorValue("0")
public class StoredFile extends Upload {

    /**
     * Whether this entry represents a folder upload (ZIP archive with a manifest).
     */
    @Column(name = "folder_upload")
    public boolean folderUpload;

    /**
     * Display name of the uploaded folder; {@code null} for non-folder uploads.
     */
    @Column(name = "folder_name")
    public String folderName;

    /**
     * JSON array describing the folder's file tree, stored as TEXT; {@code null} for single-file uploads.
     */
    @Column(name = "folder_manifest", columnDefinition = "TEXT")
    public String folderManifest;
}
