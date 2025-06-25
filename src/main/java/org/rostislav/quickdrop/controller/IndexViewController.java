package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexViewController {
    private final ApplicationSettingsService applicationSettingsService;

    public IndexViewController(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    @GetMapping("/")
    public String getIndexPage() {
        String home = applicationSettingsService.getDefaultHomePage();
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
