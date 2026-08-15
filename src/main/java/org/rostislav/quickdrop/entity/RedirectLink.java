package org.rostislav.quickdrop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/**
 * A short link that redirects to an arbitrary external URL — the general-purpose
 * link-shortener target type, as opposed to {@link UploadShareLink} which points at a
 * file/paste already stored in this instance.
 *
 * <p>{@link #targetUrl} is always the normalized, absolute form produced by
 * {@link org.rostislav.quickdrop.service.UrlNormalizationService} — scheme always present.
 * See {@link org.rostislav.quickdrop.service.LinkGuard} for why that invariant matters.
 */
@Entity
@DiscriminatorValue("REDIRECT")
public class RedirectLink extends ShortLink {
    @Column(name = "target_url")
    public String targetUrl;
    @Column(name = "title")
    public String title;

    public RedirectLink() {
    }
}
