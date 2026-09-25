package org.rostislav.quickdrop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A text paste. {@code JOINED} inheritance from {@link Upload} with no extra columns --
 * discriminator {@code "1"} in {@code upload.paste}. Content is stored on disk at
 * {@code {storagePath}/{uuid}} like file uploads; {@link Upload#name} holds the title
 * with a {@code .txt}/{@code .md} extension.
 */
@Entity
@Table(name = "paste")
@DiscriminatorValue("1")
public class Paste extends Upload {

    /**
     * When {@code true} the paste content is locked permanently — it can no longer
     * be edited by anyone (even if an edit password is set).
     */
    public boolean immutable;

    /**
     * When {@code true} the paste password only guards <em>editing</em>; the content
     * is still publicly viewable without a password (and therefore stored unencrypted).
     * When {@code false} (default) the password protects both viewing and editing.
     */
    @Column(name = "edit_only")
    public boolean editOnly;

    @Override
    public boolean isPaste() {
        return true;
    }

    @Override
    public boolean isImmutable() {
        return immutable;
    }

    @Override
    public boolean isEditOnly() {
        return editOnly;
    }
}
