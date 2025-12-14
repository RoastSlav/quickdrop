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

import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;

@Controller
public class ShareViewController {
    private static final Logger logger = LoggerFactory.getLogger(ShareViewController.class);
    private final FileService fileService;
    private final AnalyticsService analyticsService;

    public ShareViewController(FileService fileService, AnalyticsService analyticsService) {
        this.fileService = fileService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/share/{token}")
    public String viewSharedFile(@PathVariable String token, Model model) {
        Optional<ShareTokenEntity> tokenEntity = fileService.getShareTokenEntityByToken(token);

        if (tokenEntity.isEmpty() || !validateShareToken(tokenEntity.get())) {
            return "invalid-share-link";
        }

        FileEntity file = tokenEntity.get().file;
        if (file == null) {
            return "redirect:/file/list";
        }

        model.addAttribute("file", new FileEntityView(file, analyticsService.getTotalDownloadsByFile(file.uuid)));
        model.addAttribute("downloadLink", "/api/file/download/" + token);

        logger.info("Accessed shared file view for file UUID: {} via short link", file.uuid);
        return "file-share-view";
    }
}
