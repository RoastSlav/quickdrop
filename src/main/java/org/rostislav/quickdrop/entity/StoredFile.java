package org.rostislav.quickdrop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A file or bundle upload. {@code JOINED} inheritance from {@link Upload} -- discriminator
 * {@code "0"} in {@code upload.paste}; archive fields live in the {@code file} table.
 *
 * <p>An archive upload is a ZIP with a JSON manifest describing the original directory tree;
 * when {@link #archiveUpload} is {@code false} the archive fields are null/false. Columns keep
 * the original {@code folder_*} names -- renaming would need a migration for no gain.
 *
 * <p>Fields are public; there are no getters/setters.
 */
@Entity
@Table(name = "file")
@DiscriminatorValue("0")
public class StoredFile extends Upload {

    @Column(name = "folder_upload")
    public boolean archiveUpload;

    /**
     * Label for the archive's root, not the stored file name; {@code null} for plain uploads.
     */
    @Column(name = "folder_name")
    public String archiveName;

    /**
     * JSON array describing the archive's file tree, stored as TEXT; {@code null} for plain uploads.
     */
    @Column(name = "folder_manifest", columnDefinition = "TEXT")
    public String archiveManifest;
}
