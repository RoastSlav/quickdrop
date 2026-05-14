package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
    private final FileService fileService;
    private final AnalyticsService analyticsService;
    private final PasswordEncoder passwordEncoder;

    public ShareViewController(FileService fileService, AnalyticsService analyticsService, PasswordEncoder passwordEncoder) {
        this.fileService = fileService;
        this.analyticsService = analyticsService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Renders the appropriate view for a share-link URL.
     *
     * <p>For new-style encrypted tokens the {@code key} query parameter is verified
     * against the stored BCrypt hash before the session attribute is written.
     *
     * @param token   the short token string from the URL path
     * @param key     the plaintext share key (only present for AES-encrypted files)
     * @param request the HTTP request (for session key storage)
     * @param model   the Spring MVC model populated for the template
     * @return {@code "invalid-share-link"} if the token is invalid or the key doesn't
     * match; {@code "file-share-preparing"} if the sidecar is not yet ready;
     * {@code "file-share-view"} otherwise
     */
    @GetMapping("/{token}")
    public String viewSharedFile(@PathVariable String token,
                                 @RequestParam(required = false) String key,
                                 HttpServletRequest request,
                                 Model model) {
        Optional<ShareTokenEntity> tokenEntity = fileService.getShareTokenEntityByToken(token);

        if (tokenEntity.isEmpty() || !validateShareToken(tokenEntity.get())) {
            return "invalid-share-link";
        }

        ShareTokenEntity shareToken = tokenEntity.get();

        if (shareToken.shareKeyHash != null) {
            if (key == null || !passwordEncoder.matches(key, shareToken.shareKeyHash)) {
                return "invalid-share-link";
            }
            request.getSession().setAttribute("share-key-" + token, key);
        }

        FileEntity file = shareToken.file;
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
}
