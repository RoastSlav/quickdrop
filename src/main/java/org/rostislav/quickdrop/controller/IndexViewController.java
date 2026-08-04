package org.rostislav.quickdrop.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Handles the application root and generic error page.
 *
 * <p>The root {@code GET /} redirects to the configured default home page. The
 * effective destination depends on the {@code defaultHomePage} setting and whether
 * the targeted feature (paste or file list) is currently enabled:
 * <ol>
 *   <li>{@code "paste"} + pastebin enabled → {@code /file/paste/new}</li>
 *   <li>{@code "list"} + file list enabled → {@code /file/list}</li>
 *   <li>anything else → {@code /file/upload}</li>
 * </ol>
 */
@Controller
public class IndexViewController {
    private final ApplicationSettingsService applicationSettingsService;

    public IndexViewController(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    @GetMapping("/")
    public String getIndexPage() {
        String home = applicationSettingsService.getDefaultHomePage();

        if ("none".equalsIgnoreCase(home)) {
            return "service-unavailable";
        }
        if ("paste".equalsIgnoreCase(home) && applicationSettingsService.isPastebinEnabled()) {
            return "redirect:/file/paste/new";
        }
        if ("list".equalsIgnoreCase(home) && applicationSettingsService.isFileListPageEnabled()) {
            return "redirect:/file/list";
        }
        // "upload" or fallback — only redirect there if uploads are enabled
        if (applicationSettingsService.isUploadEnabled() && !applicationSettingsService.isUploadAdminOnly()) {
            return "redirect:/file/upload";
        }
        // Uploads disabled: cascade to other enabled features
        if (applicationSettingsService.isFileListPageEnabled()) {
            return "redirect:/file/list";
        }
        if (applicationSettingsService.isPastebinEnabled()) {
            return "redirect:/file/paste/new";
        }
        return "service-unavailable";
    }

    @GetMapping("/error")
    public String getErrorPage(HttpServletRequest request, Model model) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr instanceof Integer ? (Integer) statusAttr : 0;
        model.addAttribute("isNotFound", status == 404);
        return "error";
    }
}
