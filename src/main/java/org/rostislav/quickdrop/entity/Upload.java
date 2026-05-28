package org.rostislav.quickdrop.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * Base entity for all user-provided content stored by the application.
 *
 * <p>Uses JPA {@link InheritanceType#JOINED} inheritance: this entity maps to the
 * {@code upload} table, which holds the fields common to both binary/text file uploads
 * and paste entries.  The two concrete subtypes are:
 * <ul>
 *   <li>{@link StoredFile} — a binary or text file upload stored on disk, optionally
 *       containing folder-upload metadata.</li>
 *   <li>{@link Paste} — a short text snippet created via the pastebin interface.</li>
 * </ul>
 *
 * <p>The integer {@code paste} column in {@code upload} acts as the JPA discriminator:
 * {@code 0} → {@link StoredFile}, {@code 1} → {@link Paste}.
 *
 * <p>All fields are {@code public} for direct access in service and repository code;
 * there are no getter/setter accessor methods.
 */
@Entity
@Table(name = "upload")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "paste", discriminatorType = DiscriminatorType.INTEGER)
public abstract class Upload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * Original filename as provided by the uploader, or the paste title with extension.
     */
    public String name;

    /**
     * Unique identifier used as the on-disk filename and as the URL path segment.
     */
    public String uuid;

    /**
     * Optional human-readable description provided at upload time.
     */
    public String description;

    /**
     * Content size in bytes. For pastes, this is the UTF-8 byte length.
     */
    public long size;

    /**
     * When {@code true} this upload is exempt from the scheduled age-based deletion.
     */
    @Column(name = "keep_indefinitely")
    public boolean keepIndefinitely;

    /**
     * Date the record was first persisted; set automatically by {@link #prePersist()}.
     */
    @Column(name = "upload_date")
    public LocalDate uploadDate;

    /**
     * BCrypt hash of the access password, or {@code null} if the upload is not
     * password-protected.
     */
    @Column(name = "password_hash")
    public String passwordHash;

    /**
     * When {@code true} this upload is not shown on the public {@code /file/list} page.
     */
    public boolean hidden;

    /**
     * When {@code true} the upload has been soft-deleted: the physical file no longer
     * exists on disk.  The DB record and all activity log rows are retained so that
     * admins can still view the page and the activity log can still link back to it.
     */
    public boolean deleted;

    /**
     * When {@code true} the file contents on disk are AES-encrypted.
     * Always {@code false} for pastes that were created without a password.
     */
    public boolean encrypted;

    /**
     * Returns {@code true} if this upload is a text paste, {@code false} if it is a
     * stored file.
     *
     * <p>Exposed as a Java bean getter so that Spring EL / Thymeleaf can resolve the
     * {@code file.paste} property expression without requiring a plain {@code paste}
     * field on every subtype.  Subclasses override this as needed.
     *
     * @return {@code false} by default; {@link Paste} overrides to return {@code true}
     */
    public boolean isPaste() {
        return false;
    }

    /**
     * Returns {@code true} if this upload is a permanently immutable paste.
     *
     * <p>Exposed as a Java bean getter so Thymeleaf can resolve {@code file.immutable}
     * on any {@code Upload} subtype without a cast.
     *
     * @return {@code false} by default; {@link Paste} overrides to return the stored flag
     */
    public boolean isImmutable() {
        return false;
    }

    /**
     * Returns {@code true} if this upload is a paste whose password protects editing only
     * (the content is viewable without a password).
     *
     * <p>Exposed as a Java bean getter so Thymeleaf can resolve {@code file.editOnly}
     * on any {@code Upload} subtype without a cast.
     *
     * @return {@code false} by default; {@link Paste} overrides to return the stored flag
     */
    public boolean isEditOnly() {
        return false;
    }

    /**
     * Sets {@link #uploadDate} to today before the first database INSERT.
     */
    @PrePersist
    public void prePersist() {
        uploadDate = LocalDate.now();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id=" + id + ", name='" + name + "', uuid='" + uuid + "'}";
    }
}
