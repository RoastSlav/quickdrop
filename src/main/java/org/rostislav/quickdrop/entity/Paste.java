package org.rostislav.quickdrop.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Represents a text paste created via the pastebin interface.
 *
 * <p>Extends {@link Upload} with no additional fields.  Maps to the {@code paste}
 * table in a JPA {@code JOINED} inheritance hierarchy; all data lives in the
 * {@code upload} base table.
 *
 * <p>A discriminator value of {@code "1"} in the {@code upload.paste} column
 * identifies rows belonging to this subtype.
 *
 * <p>Paste content is stored on disk at the path {@code {storagePath}/{uuid}},
 * identical to file uploads.  The {@link Upload#name} field holds the paste title
 * with a {@code .txt} or {@code .md} extension.
 */
@Entity
@Table(name = "paste")
@DiscriminatorValue("1")
public class Paste extends Upload {
    /**
     * Always returns {@code true} for paste entries.
     *
     * @return {@code true}
     */
    @Override
    public boolean isPaste() {
        return true;
    }
}
