package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.ShortLink;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.LinkGuard;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.service.ShortCodeService;
import org.rostislav.quickdrop.service.ShortLinkService;
import org.rostislav.quickdrop.service.UrlNormalizationService;
import org.rostislav.quickdrop.model.RequesterInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.getRequesterInfo;
import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;

/**
 * Serves the general link-shortener create page and the {@code /s/{code}} resolver.
 *
 * <p>The {@code s} prefix is a <strong>literal</strong> route segment, not a
 * {@code {prefix}} path variable — deliberately, even though {@link ShortCodeService#DEFAULT_PATH_PREFIX}
 * is meant to become a configurable setting later. A variable first segment
 * (e.g. {@code @GetMapping("/{prefix}/{code}")}) structurally matches <em>any</em> two-segment
 * path, including static assets like {@code /js/app.js} or {@code /css/site.css} — and Spring
 * resolves annotated controllers before it ever consults the static-resource handler, so that
 * pattern would silently 404 every such asset site-wide. Making the prefix configurable
 * without reintroducing that collision needs either a fixed allowlist of literal prefixes or a
 * regex excluding reserved top-level segments (kept in sync with
 * {@link ShortCodeService}'s reserved-word list) — a problem for whenever the prefix setting
 * is added, not solved here.
 *
 * <p>Visit accounting (use-count increment, remaining-uses decrement) intentionally does
 * <em>not</em> happen just from rendering this page — mirrors how {@code /share/{token}}
 * (page view) is separate from {@code /api/file/download/{token}} (the actual byte-serving
 * action that decrements). Viewing the interstitial must not consume a limited-use link;
 * only completing the redirect via {@link #confirm} does.
 */
@Controller
public class ShortLinkViewController {
    private static final Logger logger = LoggerFactory.getLogger(ShortLinkViewController.class);

    private final ShortLinkRepository shortLinkRepository;
    private final ShortLinkService shortLinkService;
    private final SessionService sessionService;
    private final LinkGuard linkGuard;
    private final UrlNormalizationService urlNormalizationService;
    private final ApplicationSettingsService applicationSettingsService;

    public ShortLinkViewController(ShortLinkRepository shortLinkRepository,
                                   ShortLinkService shortLinkService,
                                   SessionService sessionService,
                                   LinkGuard linkGuard,
                                   UrlNormalizationService urlNormalizationService,
                                   ApplicationSettingsService applicationSettingsService) {
        this.shortLinkRepository = shortLinkRepository;
        this.shortLinkService = shortLinkService;
        this.sessionService = sessionService;
        this.linkGuard = linkGuard;
        this.urlNormalizationService = urlNormalizationService;
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * Creation-page gating only — existing links keep resolving at {@code /s/{code}} even
     * after an admin disables the feature, matching how {@code shareLinksEnabled} already
     * behaves (stops minting, doesn't break links already sent to other people).
     */
    @GetMapping("/link/new")
    public String newLinkForm(HttpServletRequest request, Model model) {
        boolean isAdmin = sessionService.hasValidAdminSession(request);
        if (!applicationSettingsService.isShortenerEnabled()
                || (applicationSettingsService.isShortenerAdminOnly() && !isAdmin)) {
            return "redirect:/";
        }
        // Drives the plain-language security notice on the page — only listing
        // protections that are actually active, not a static claim.
        model.addAttribute("shortenerInterstitialMode", applicationSettingsService.getShortenerInterstitialMode());
        model.addAttribute("shortenerDomainRuleMode", applicationSettingsService.getShortenerDomainRuleMode());
        model.addAttribute("reputationCheckEnabled", applicationSettingsService.isReputationCheckEnabled());
        return "link-new";
    }

    /**
     * Resolves a short code. Upload-share links forward to the existing, unchanged
     * {@code /share/{token}} flow. Redirect links either show an interstitial (default for
     * non-admin visitors) or redirect immediately (admin sessions), consuming the link's
     * use budget only on the path that actually redirects.
     */
    @GetMapping("/" + ShortCodeService.DEFAULT_PATH_PREFIX + "/{code}")
    public String resolve(@PathVariable String code, HttpServletRequest request, Model model) {
        Optional<ShortLink> found = shortLinkRepository.findByCode(code);
        if (found.isEmpty() || !validateShareToken(found.get())) {
            return "invalid-share-link";
        }
        ShortLink link = found.get();

        if (link instanceof UploadShareLink) {
            return "redirect:/share/" + code;
        }

        RedirectLink redirectLink = (RedirectLink) link;
        if (!linkGuard.checkForRedirect(redirectLink.targetUrl).allowed()) {
            RequesterInfo blockedInfo = getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
            shortLinkService.logBlockedVisit(link, blockedInfo.ipAddress(), blockedInfo.userAgent());
            return "invalid-share-link";
        }

        boolean isAdmin = sessionService.hasValidAdminSession(request);
        if (!showInterstitial(isAdmin)) {
            // The view and the visit collapse into one step here, so consume now.
            RequesterInfo info = getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled());
            shortLinkService.resolveAndRecordVisit(code, info.ipAddress(), info.userAgent());
            return "redirect:" + redirectLink.targetUrl;
        }

        model.addAttribute("prefix", ShortCodeService.DEFAULT_PATH_PREFIX);
        model.addAttribute("code", code);
        model.addAttribute("targetUrl", redirectLink.targetUrl);
        model.addAttribute("displayUrl", urlNormalizationService.toDisplayForm(redirectLink.targetUrl));
        model.addAttribute("createdAt", redirectLink.createdAt);
        return "short-link-preview";
    }

    /**
     * Confirms a redirect-link interstitial: re-validates, records the visit, and redirects.
     */
    @GetMapping("/" + ShortCodeService.DEFAULT_PATH_PREFIX + "/{code}/go")
    public String confirm(@PathVariable String code) {
        Optional<ShortLink> resolved = shortLinkService.resolveAndRecordVisit(code);
        if (resolved.isEmpty()) {
            return "invalid-share-link";
        }
        ShortLink link = resolved.get();
        if (link instanceof UploadShareLink) {
            return "redirect:/share/" + code;
        }
        return "redirect:" + ((RedirectLink) link).targetUrl;
    }

    /**
     * @return whether the interstitial should be shown, per {@code shortenerInterstitialMode}
     *         ({@code ALWAYS}, {@code NEVER}, or the default {@code NON_ADMIN} — skip only
     *         for admin sessions)
     */
    private boolean showInterstitial(boolean isAdmin) {
        String mode = applicationSettingsService.getShortenerInterstitialMode();
        return switch (mode == null ? "NON_ADMIN" : mode) {
            case "ALWAYS" -> true;
            case "NEVER" -> false;
            default -> !isAdmin;
        };
    }
}
