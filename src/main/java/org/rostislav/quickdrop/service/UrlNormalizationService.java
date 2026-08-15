package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.NormalizedUrl;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns whatever a user typed as a link-shortener destination into an absolute URL, and
 * separately produces a short human-facing display form of an already-normalized URL.
 *
 * <p>These are deliberately two different things. {@link #normalize} always yields (and
 * {@link LinkGuard} always persists) a URL with an <strong>explicit scheme</strong> —
 * storing a scheme-less string and reconstructing it at redirect time is exactly how
 * open-redirect bugs happen, since {@code //evil.com} is a valid protocol-relative
 * reference that would send visitors off-site. {@link #toDisplayForm} instead strips the
 * scheme and a leading {@code www.} for display only, in the admin table, interstitial, and
 * success card — it never touches what's actually stored, because {@code www.example.com}
 * and {@code example.com} can be different servers and rewriting the real target would
 * silently change where the link goes.
 */
@Service
public class UrlNormalizationService {
    private static final int MAX_URL_LENGTH = 2048;
    /**
     * Matches an RFC 3986 scheme prefix ({@code scheme:}), not just {@code scheme://} —
     * broad enough that opaque-form schemes like {@code javascript:} or {@code mailto:} are
     * recognized as already scheme-qualified and passed through unmodified rather than being
     * mangled into garbage like {@code https://javascript:alert(1)}; letting them parse as-is
     * means they reach {@link UrlSafetyValidator}'s scheme allowlist intact, which is what
     * actually rejects them.
     *
     * <p>Deliberately narrower than the RFC, which technically permits {@code .} in a
     * scheme: allowing it here would misdetect a bare {@code host:port} address like
     * {@code example.com:8080/page} as scheme {@code example.com} (no real-world scheme
     * contains a dot, so excluding it resolves the ambiguity in favor of the host:port
     * reading, which is what a user pasting a schemeless address actually means).
     */
    private static final Pattern EXPLICIT_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+-]*:");

    /**
     * Parses raw user input into an absolute, explicit-scheme URL.
     *
     * @param rawInput the raw text the user typed
     * @return the parsed and display forms
     * @throws InvalidUrlException if the input can't be turned into a usable absolute URL —
     *                             the exception message is safe to show the user directly
     */
    public NormalizedUrl normalize(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new InvalidUrlException("Enter a web address.");
        }
        String trimmed = rawInput.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("That address is too long.");
        }

        String withScheme = ensureScheme(trimmed);

        URI uri;
        try {
            uri = new URI(withScheme);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("That doesn't look like a valid web address.");
        }

        // Only http(s) URLs are required to have a host at this stage. Other schemes
        // (javascript:, mailto:, data:, ...) are opaque and legitimately host-less — they're
        // left to UrlSafetyValidator's scheme allowlist, which rejects them with a more
        // specific "not http/https" message than a generic "invalid address" here would give.
        boolean isHttpOrHttps = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (isHttpOrHttps && (uri.getHost() == null || uri.getHost().isBlank())) {
            throw new InvalidUrlException("That doesn't look like a valid web address.");
        }

        String absoluteForm = uri.toString();
        return new NormalizedUrl(uri, absoluteForm, toDisplayForm(absoluteForm));
    }

    /**
     * Strips the scheme and a leading {@code www.} host label for display purposes —
     * mirrors what a browser's address bar already does. Falls back to returning the input
     * unchanged if it isn't a parseable absolute URL.
     */
    public String toDisplayForm(String absoluteUrl) {
        try {
            URI uri = URI.create(absoluteUrl);
            String host = uri.getHost();
            if (host == null) {
                return absoluteUrl;
            }
            String displayHost = host.toLowerCase(Locale.ROOT).startsWith("www.")
                    ? host.substring(4)
                    : host;
            StringBuilder rest = new StringBuilder();
            if (uri.getRawPath() != null) {
                rest.append(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                rest.append('?').append(uri.getRawQuery());
            }
            return displayHost + rest;
        } catch (IllegalArgumentException e) {
            return absoluteUrl;
        }
    }

    /**
     * Prepends {@code https://} when the input has no explicit scheme. A leading {@code //}
     * (a protocol-relative reference) is handled the same way — the {@code //} is stripped
     * and {@code https:} is prepended — so the result always carries an explicit scheme
     * regardless of how the user typed it.
     */
    private String ensureScheme(String input) {
        if (input.startsWith("//")) {
            return "https:" + input;
        }
        if (EXPLICIT_SCHEME.matcher(input).find()) {
            return input;
        }
        return "https://" + input;
    }
}
