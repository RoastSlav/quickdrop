package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.stereotype.Controller;
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
        if ("paste".equalsIgnoreCase(home) && applicationSettingsService.isPastebinEnabled()) {
            return "redirect:/file/paste/new";
        }
        if ("list".equalsIgnoreCase(home) && applicationSettingsService.isFileListPageEnabled()) {
            return "redirect:/file/list";
        }
        return "redirect:/file/upload";
    }

    @GetMapping("/error")
    public String getErrorPage() {
        return "error";
    }
}
