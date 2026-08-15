package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.ShortLink;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.LinkRejectedException;
import org.rostislav.quickdrop.service.QrCodeService;
import org.rostislav.quickdrop.service.QrGenerationException;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.service.ShortCodeService;
import org.rostislav.quickdrop.service.ShortLinkService;
import org.rostislav.quickdrop.util.FileUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.getRequesterInfo;
import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;
import static org.springframework.http.ResponseEntity.ok;

/**
 * REST API for short-link-related media. Starts with QR code generation, which applies
 * immediately to every existing upload-share link with no new UI required; creation and
 * resolution endpoints for plain-URL redirect links ({@code POST /api/link}, etc.) are
 * added here in a later change.
 */
@RestController
@RequestMapping("/api/link")
public class ShortLinkRestController {
    private final ShortLinkRepository shortLinkRepository;
    private final QrCodeService qrCodeService;
    private final ShortLinkService shortLinkService;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;

    public ShortLinkRestController(ShortLinkRepository shortLinkRepository, QrCodeService qrCodeService,
                                   ShortLinkService shortLinkService,
                                   ApplicationSettingsService applicationSettingsService,
                                   SessionService sessionService) {
        this.shortLinkRepository = shortLinkRepository;
        this.qrCodeService = qrCodeService;
        this.shortLinkService = shortLinkService;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
    }

    /**
     * Creates a redirect (plain URL-shortener) link.
     *
     * @param url             the destination to shorten; scheme is optional (defaults to https)
     * @param expirationDate  optional expiry date
     * @param maxUses         optional use limit; {@code null} means unlimited
     * @param customAlias     optional human-chosen code; a random one is generated when blank
     * @return 200 with {@code code}/{@code shortUrl}/{@code qrSvgUrl}/{@code qrPngUrl}, 403 when
     *         the feature (or, for non-admins, the feature itself) is disabled, or 400 with
     *         {@code message} when the destination or alias is rejected
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> createLink(@RequestParam String url,
                                                           @RequestParam(required = false) LocalDate expirationDate,
                                                           @RequestParam(required = false) Integer maxUses,
                                                           @RequestParam(required = false) String customAlias,
                                                           HttpServletRequest request) {
        boolean isAdmin = sessionService.hasValidAdminSession(request);
        if (!applicationSettingsService.isShortenerEnabled()
                || (applicationSettingsService.isShortenerAdminOnly() && !isAdmin)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "The link shortener is disabled."));
        }
        if (maxUses != null && maxUses < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Max uses cannot be negative."));
        }
        String creatorIp = getRequesterInfo(request, applicationSettingsService.isTrustedProxyEnabled()).ipAddress();
        try {
            RedirectLink link = shortLinkService.createRedirectLink(
                    url, expirationDate, maxUses, customAlias, isAdmin, creatorIp);
            String baseUrl = FileUtils.getBaseUrl(request);
            String shortUrl = baseUrl + "/" + ShortCodeService.DEFAULT_PATH_PREFIX + "/" + link.code;
            return ok(Map.of(
                    "code", link.code,
                    "shortUrl", shortUrl,
                    "targetUrl", link.targetUrl,
                    "qrSvgUrl", "/api/link/" + link.code + "/qr.svg",
                    "qrPngUrl", "/api/link/" + link.code + "/qr.png"
            ));
        } catch (LinkRejectedException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Returns an SVG QR code encoding the full URL for the given link code.
     *
     * @param code the short link code
     * @param size rendered width/height in pixels; clamped by {@link QrCodeService}
     * @return 200 with the SVG body, or 404 if the code doesn't exist / is no longer valid
     */
    @GetMapping(value = "/{code}/qr.svg", produces = "image/svg+xml")
    public ResponseEntity<String> qrSvg(@PathVariable String code,
                                        @RequestParam(defaultValue = "256") int size,
                                        HttpServletRequest request) {
        Optional<String> target = resolveTargetUrl(code, request);
        if (target.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            String svg = qrCodeService.renderSvg(target.get(), size, "#000000", "#ffffff");
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(svg);
        } catch (QrGenerationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).build();
        }
    }

    /**
     * Returns a PNG QR code encoding the full URL for the given link code.
     *
     * @param code the short link code
     * @param size rendered width/height in pixels; clamped by {@link QrCodeService}
     * @return 200 with the PNG body, or 404 if the code doesn't exist / is no longer valid
     */
    @GetMapping(value = "/{code}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrPng(@PathVariable String code,
                                        @RequestParam(defaultValue = "256") int size,
                                        HttpServletRequest request) {
        Optional<String> target = resolveTargetUrl(code, request);
        if (target.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            byte[] png = qrCodeService.renderPng(target.get(), size, "#000000", "#ffffff");
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(png);
        } catch (QrGenerationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).build();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Resolves {@code code} to the absolute URL a QR code for it should encode, or empty if
     * the code doesn't exist or the link is no longer valid (expired/exhausted).
     *
     * <p>Always encodes the short link itself, never a redirect link's raw target — scanning
     * the code must go through the resolver (safety re-check, interstitial, use counting),
     * the same as clicking the short link would.
     */
    private Optional<String> resolveTargetUrl(String code, HttpServletRequest request) {
        Optional<ShortLink> link = shortLinkRepository.findByCode(code);
        if (link.isEmpty() || !validateShareToken(link.get())) {
            return Optional.empty();
        }
        String baseUrl = FileUtils.getBaseUrl(request);
        if (link.get() instanceof UploadShareLink) {
            return Optional.of(baseUrl + FileUtils.getSharePath(code));
        }
        if (link.get() instanceof RedirectLink) {
            return Optional.of(baseUrl + "/" + ShortCodeService.DEFAULT_PATH_PREFIX + "/" + code);
        }
        return Optional.empty();
    }
}
