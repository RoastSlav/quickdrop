package org.rostislav.quickdrop.service;

import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Rejects destinations that are unsafe to redirect to: non-http(s) schemes, credentials
 * embedded in the authority, and hosts resolving to a private/loopback/link-local address.
 *
 * <p>Called both when a link is created and again every time it is resolved (see
 * {@link LinkGuard}) — a host that resolved to a public address when the link was created
 * can be repointed at {@code 127.0.0.1} later (DNS rebinding), so this is not a one-time gate.
 */
@Service
public class UrlSafetyValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * @return empty if {@code uri} is safe to redirect to, or a plain-language message
     *         (safe to show the user) explaining why it was rejected
     */
    public Optional<String> validate(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return Optional.of("Only http:// and https:// links can be shortened.");
        }
        if (uri.getUserInfo() != null) {
            return Optional.of("Links with embedded credentials aren't allowed.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.of("That doesn't look like a valid web address.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Optional.of("That address couldn't be resolved.");
        }

        for (InetAddress address : addresses) {
            if (isPrivateOrReserved(address)) {
                return Optional.of("Links pointing into a private network aren't allowed.");
            }
        }

        return Optional.empty();
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique local addresses (fc00::/7) — isSiteLocalAddress() only recognizes the
        // deprecated fec0::/10 range, not this one.
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
