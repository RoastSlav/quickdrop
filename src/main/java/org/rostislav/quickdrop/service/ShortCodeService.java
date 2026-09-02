package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates and validates short-link codes shared across every {@code ShortLink} subtype.
 *
 * <p>Random codes use {@link SecureRandom} rather than a hash-of-metadata approach — a
 * short link (this one included, via {@code UploadShareLink}) gates access to a file or
 * redirects a visitor somewhere, so its code needs an unguessable source, not just a
 * differently-seeded one.
 */
@Service
public class ShortCodeService {
    /**
     * Default random-code length. Short on purpose — every code is namespaced under a path
     * prefix (see {@code shortenerPathPrefix} setting) and the resolver route is
     * rate-limited, which is the intended defense against enumeration rather than length
     * alone. Admins running a higher-value instance can raise this in settings; a longer
     * default doesn't invalidate codes already issued at 5, since lookup is by exact match.
     */
    public static final int DEFAULT_LENGTH = 5;

    /** Path prefix redirect links resolve under ({@code /s/{code}}). */
    public static final String DEFAULT_PATH_PREFIX = "s";

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int MAX_COLLISION_RETRIES = 5;
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,64}$");

    /**
     * Path segments a custom alias must not collide with — every one of these is either a
     * top-level route this app already serves, or a static-resource prefix.
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "admin", "api", "file", "share", "password", "branding",
            "css", "js", "images", "actuator", "static", "s", "link"
    );

    private final SecureRandom random = new SecureRandom();
    private final ShortLinkRepository shortLinkRepository;

    public ShortCodeService(ShortLinkRepository shortLinkRepository) {
        this.shortLinkRepository = shortLinkRepository;
    }

    /**
     * Generates a random code of the given length that doesn't already exist.
     *
     * @throws IllegalStateException if no unique code was found within a bounded number of
     *                               attempts — fails loudly rather than looping forever
     */
    public String generateUniqueCode(int length) {
        for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
            String candidate = randomCode(length);
            if (!shortLinkRepository.existsByShareToken(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not generate a unique short code of length " + length + " after " + MAX_COLLISION_RETRIES + " attempts");
    }

    private String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Validates a user-chosen alias: format, reserved words, and case-insensitive
     * uniqueness (to avoid {@code PayPal} and {@code paypal} resolving to different links).
     *
     * @return valid, or invalid with a message safe to show the user
     */
    public AliasVerdict validateAlias(String alias) {
        if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
            return AliasVerdict.invalid("Use 3-64 letters, numbers, hyphens, or underscores.");
        }
        if (RESERVED_WORDS.contains(alias.toLowerCase(Locale.ROOT))) {
            return AliasVerdict.invalid("That word is reserved and can't be used as a link.");
        }
        if (shortLinkRepository.existsByCodeIgnoreCase(alias)) {
            return AliasVerdict.invalid("That link is already taken.");
        }
        return AliasVerdict.valid();
    }

    /**
     * @return {@code true} if {@code word} is reserved and can't be used as a custom alias
     */
    public boolean isReserved(String word) {
        return word != null && RESERVED_WORDS.contains(word.toLowerCase(Locale.ROOT));
    }

    public record AliasVerdict(boolean ok, String message) {
        public static AliasVerdict valid() {
            return new AliasVerdict(true, null);
        }

        public static AliasVerdict invalid(String message) {
            return new AliasVerdict(false, message);
        }
    }
}
