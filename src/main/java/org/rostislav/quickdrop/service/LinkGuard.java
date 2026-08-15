package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.LinkVerdict;
import org.rostislav.quickdrop.model.NormalizedUrl;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Single entry point for vetting a redirect-link destination — normalization, scheme/IP
 * safety, the admin domain allow/blocklist, and threat-feed reputation (via
 * {@link ReputationCheckService}) all live behind this facade rather than being called
 * piecemeal from controllers. There are
 * exactly two call sites meant to exist in the codebase: {@link #checkForCreation} when a
 * link is created, and {@link #checkForRedirect} every time one is resolved. Safety must be
 * re-checked on resolve, not just at creation, because a host that was public when the link
 * was created can be repointed at a private address later (DNS rebinding) — and because an
 * admin can add a domain to the blocklist after links to it already exist.
 *
 * <p>The stored/redirected-to URL is always the normalized <em>absolute</em> form, scheme
 * included — see {@link UrlNormalizationService} for why a scheme-less stored value must
 * never happen.
 */
@Service
public class LinkGuard {
    private final UrlNormalizationService normalizationService;
    private final UrlSafetyValidator safetyValidator;
    private final ApplicationSettingsService applicationSettingsService;
    private final ReputationCheckService reputationCheckService;

    public LinkGuard(UrlNormalizationService normalizationService, UrlSafetyValidator safetyValidator,
                     ApplicationSettingsService applicationSettingsService, ReputationCheckService reputationCheckService) {
        this.normalizationService = normalizationService;
        this.safetyValidator = safetyValidator;
        this.applicationSettingsService = applicationSettingsService;
        this.reputationCheckService = reputationCheckService;
    }

    /**
     * Normalizes and validates a destination a user is trying to shorten.
     *
     * @param rawUrl the raw text the user typed
     * @return an allowed verdict carrying the absolute form to persist, or a blocked verdict
     *         with a message safe to show the user
     */
    public LinkVerdict checkForCreation(String rawUrl) {
        NormalizedUrl normalized;
        try {
            normalized = normalizationService.normalize(rawUrl);
        } catch (InvalidUrlException e) {
            return LinkVerdict.block("invalid_url", e.getMessage());
        }

        Optional<String> unsafe = safetyValidator.validate(normalized.uri());
        if (unsafe.isPresent()) {
            return LinkVerdict.block("unsafe_destination", unsafe.get());
        }

        Optional<String> domainRejected = checkDomainRules(normalized.uri().getHost());
        if (domainRejected.isPresent()) {
            return LinkVerdict.block("domain_rejected", domainRejected.get());
        }

        Optional<String> reputationRejected = reputationCheckService.check(normalized.uri());
        if (reputationRejected.isPresent()) {
            return LinkVerdict.block("reputation_blocked", reputationRejected.get());
        }

        return LinkVerdict.allow(normalized.absoluteForm());
    }

    /**
     * Re-validates a previously-stored absolute URL immediately before redirecting to it.
     *
     * <p>Takes the already-normalized absolute URL string rather than a redirect-link
     * entity — that entity type is introduced in a later change, at which point this method
     * gains an overload rather than being changed, so callers added before then won't break.
     *
     * @param storedAbsoluteUrl the URL exactly as persisted by {@link #checkForCreation}
     */
    public LinkVerdict checkForRedirect(String storedAbsoluteUrl) {
        URI uri;
        try {
            uri = URI.create(storedAbsoluteUrl);
        } catch (IllegalArgumentException e) {
            return LinkVerdict.block("invalid_url", "This link points somewhere invalid.");
        }

        Optional<String> unsafe = safetyValidator.validate(uri);
        if (unsafe.isPresent()) {
            return LinkVerdict.block("unsafe_destination", unsafe.get());
        }

        Optional<String> domainRejected = checkDomainRules(uri.getHost());
        if (domainRejected.isPresent()) {
            return LinkVerdict.block("domain_rejected", domainRejected.get());
        }

        Optional<String> reputationRejected = reputationCheckService.check(uri);
        if (reputationRejected.isPresent()) {
            return LinkVerdict.block("reputation_blocked", reputationRejected.get());
        }

        return LinkVerdict.allow(storedAbsoluteUrl);
    }

    /**
     * Applies the admin-configured {@code shortenerDomainRuleMode} — {@code BLOCKLIST}
     * rejects a listed domain (and its subdomains), {@code ALLOWLIST} rejects anything
     * <em>not</em> listed. {@code OFF} (the default) always allows.
     *
     * @return empty if allowed, or a plain-language rejection message
     */
    private Optional<String> checkDomainRules(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String mode = applicationSettingsService.getShortenerDomainRuleMode();
        if (mode == null || "OFF".equals(mode)) {
            return Optional.empty();
        }
        Set<String> rules = parseDomainRules(applicationSettingsService.getShortenerDomainRules());
        if (rules.isEmpty()) {
            return Optional.empty();
        }
        boolean matches = rules.stream().anyMatch(rule -> domainMatches(host, rule));
        if ("BLOCKLIST".equals(mode) && matches) {
            return Optional.of("This destination has been blocked by the administrator.");
        }
        if ("ALLOWLIST".equals(mode) && !matches) {
            return Optional.of("This destination isn't on the administrator's allowed list.");
        }
        return Optional.empty();
    }

    private Set<String> parseDomainRules(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> rules = new java.util.HashSet<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                rules.add(trimmed);
            }
        }
        return rules;
    }

    /** {@code host} matches {@code rule} exactly, or as a subdomain of it. */
    private boolean domainMatches(String host, String rule) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return lowerHost.equals(rule) || lowerHost.endsWith("." + rule);
    }
}
