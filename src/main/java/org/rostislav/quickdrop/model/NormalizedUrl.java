package org.rostislav.quickdrop.model;

import java.net.URI;

/**
 * The result of {@link org.rostislav.quickdrop.service.UrlNormalizationService#normalize}:
 * a parsed, absolute (scheme always present) URI, its string form for persistence, and a
 * human-facing display form (scheme and leading {@code www.} stripped).
 */
public record NormalizedUrl(URI uri, String absoluteForm, String displayForm) {
}
