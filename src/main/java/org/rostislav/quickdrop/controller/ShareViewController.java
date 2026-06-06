package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.FileQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;

/**
 * Renders the public share-link landing page for a single file.
 *
 * <p>Share tokens are validated before the page is rendered:
 * <ul>
 *   <li>Expired or exhausted tokens render {@code invalid-share-link}.</li>
 *   <li>Tokens whose sidecar re-encryption is still in progress
 *       ({@link org.rostislav.quickdrop.entity.ShareTokenEntity#sidecarReady} is
 *       {@code false}) render {@code file-share-preparing} so the recipient can
 *       refresh once the file is ready.</li>
 *   <li>Valid, ready tokens render {@code file-share-view} with a download link
 *       pointing to {@link FileRestController#downloadFile}.</li>
 * </ul>
 */
@Controller
@RequestMapping("/share")
public class ShareViewController {
    private static final Logger logger = LoggerFactory.getLogger(ShareViewController.class);
    private final FileQueryService fileQueryService;
    private final AnalyticsService analyticsService;
    private final PasswordEncoder passwordEncoder;

    public ShareViewController(FileQueryService fileQueryService, AnalyticsService analyticsService, PasswordEncoder passwordEncoder) {
        this.fileQueryService = fileQueryService;
        this.analyticsService = analyticsService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Renders the share-link landing page.
     *
     * <p>For encrypted tokens the key may arrive in two ways:
     * <ul>
     *   <li>Legacy {@code ?key=} query parameter (old links) — validated immediately.</li>
     *   <li>URL fragment {@code #key=} — the fragment is never sent by the browser, so the
     *       page renders with {@code needsKeyAuth=true} and the client JS reads the hash,
     *       then POSTs it to {@link #validateShareKey} before enabling the download.</li>
     * </ul>
     */
    @GetMapping("/{token}")
    public String viewSharedFile(@PathVariable String token,
                                 @RequestParam(required = false) String key,
                                 HttpServletRequest request,
                                 Model model) {
        Optional<ShareTokenEntity> tokenEntity = fileQueryService.getShareTokenEntityByToken(token);

        if (tokenEntity.isEmpty() || !validateShareToken(tokenEntity.get())) {
            return "invalid-share-link";
        }

        ShareTokenEntity shareToken = tokenEntity.get();

        if (shareToken.shareKeyHash != null) {
            // Backward compat: accept ?key= query param from old-style links
            String sessionKey = (String) request.getSession().getAttribute("share-key-" + token);
            if (sessionKey != null) {
                // Key already validated in a prior request — proceed
            } else if (key != null) {
                if (!passwordEncoder.matches(key, shareToken.shareKeyHash)) {
                    return "invalid-share-link";
                }
                request.getSession().setAttribute("share-key-" + token, key);
            } else {
                // New-style: key arrives via URL fragment — client JS will POST it
                Upload file = shareToken.file;
                if (file == null) return "redirect:/file/list";
                if (!shareToken.sidecarReady) {
                    model.addAttribute("fileName", file.name);
                    return "file-share-preparing";
                }
                model.addAttribute("file", new FileEntityView(file, analyticsService.getTotalDownloadsByFile(file.uuid)));
                model.addAttribute("downloadLink", "/api/file/download/" + token);
                model.addAttribute("needsKeyAuth", true);
                return "file-share-view";
            }
        }

        Upload file = shareToken.file;
        if (file == null) {
            return "redirect:/file/list";
        }

        if (!shareToken.sidecarReady) {
            model.addAttribute("fileName", file.name);
            return "file-share-preparing";
        }

        model.addAttribute("file", new FileEntityView(file, analyticsService.getTotalDownloadsByFile(file.uuid)));
        model.addAttribute("downloadLink", "/api/file/download/" + token);

        logger.info("Accessed shared file view for file UUID: {} via short link", file.uuid);
        return "file-share-view";
    }

    /**
     * Validates the share key POSTed by client JS (from the URL fragment) and stores
     * it in the HTTP session so subsequent downloads can decrypt the sidecar.
     *
     * @param token   the share token
     * @param key     the plaintext key read from {@code window.location.hash} by the client
     * @param request HTTP request for session storage
     * @return 200 OK on success, 403 on wrong key, 404 on missing/expired token
     */
    @PostMapping("/{token}/auth")
    public ResponseEntity<Map<String, String>> validateShareKey(@PathVariable String token,
                                                                @RequestParam String key,
                                                                HttpServletRequest request) {
        Optional<ShareTokenEntity> tokenEntity = fileQueryService.getShareTokenEntityByToken(token);
        if (tokenEntity.isEmpty() || !validateShareToken(tokenEntity.get())) {
            return ResponseEntity.notFound().build();
        }
        ShareTokenEntity shareToken = tokenEntity.get();
        if (shareToken.shareKeyHash == null || !passwordEncoder.matches(key, shareToken.shareKeyHash)) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid key"));
        }
        request.getSession().setAttribute("share-key-" + token, key);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
