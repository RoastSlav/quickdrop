package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalControllerAdvice {

    private final ApplicationSettingsService applicationSettingsService;

    public GlobalControllerAdvice(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    @ModelAttribute
    public void addGlobalAttributes(Model model) {
        model.addAttribute("isFileListPageEnabled", applicationSettingsService.isFileListPageEnabled());
        model.addAttribute("isAppPasswordSet", applicationSettingsService.isAppPasswordEnabled());
        model.addAttribute("isAdminDashboardButtonEnabled", applicationSettingsService.isAdminDashboardButtonEnabled());
    }
}
