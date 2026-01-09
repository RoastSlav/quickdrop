package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;

@Controller
@RequestMapping("/share")
public class ShareViewController {
    private static final Logger logger = LoggerFactory.getLogger(ShareViewController.class);
    private final FileService fileService;
    private final AnalyticsService analyticsService;

    public ShareViewController(FileService fileService, AnalyticsService analyticsService) {
        this.fileService = fileService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/{token}")
    public String viewSharedFile(@PathVariable String token, Model model) {
        logger.info("Share view requested for token={}", token);
        Optional<ShareTokenEntity> tokenEntity = fileService.getShareTokenEntityByToken(token);

        if (tokenEntity.isEmpty() || !validateShareToken(tokenEntity.get()) || !fileService.tokenSecretMatches(token, tokenEntity.get())) {
            logger.warn("Share view invalid: token={} entityPresent={} validated={} secretMatch={}", token, tokenEntity.isPresent(), tokenEntity.isPresent() && validateShareToken(tokenEntity.get()), tokenEntity.isPresent() && fileService.tokenSecretMatches(token, tokenEntity.get()));
            return "invalid-share-link";
        }

        ShareTokenEntity shareTokenEntity = tokenEntity.get();
        FileEntity file = shareTokenEntity.file;
        if (file == null) {
            return "redirect:/file/list";
        }

        model.addAttribute("file", new FileEntityView(file, analyticsService.getTotalDownloadsByFile(file.uuid)));
        String downloadToken = shareTokenEntity.publicId != null ? shareTokenEntity.publicId : token;

        model.addAttribute("downloadLink", "/api/file/download/" + downloadToken);
        model.addAttribute("secretToken", shareTokenEntity.shareToken);
        model.addAttribute("encryptionVersion", file.encryptionVersion);
        model.addAttribute("wrappedDek", shareTokenEntity.wrappedDek);
        model.addAttribute("wrapNonce", shareTokenEntity.wrapNonce);
        model.addAttribute("tokenMode", shareTokenEntity.tokenMode != null ? shareTokenEntity.tokenMode : "legacy");
        model.addAttribute("publicId", shareTokenEntity.publicId);
        model.addAttribute("fileUuid", file.uuid);

        logger.info("Accessed shared file view for file UUID: {} via short link", file.uuid);
        return "file-share-view";
    }
}
