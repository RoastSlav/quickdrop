package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.model.ApplicationSettingsEntity;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.rostislav.quickdrop.util.FileUtils.bytesToMegabytes;
import static org.rostislav.quickdrop.util.FileUtils.megabytesToBytes;

@Controller
@RequestMapping("/admin")
public class AdminViewController {
    private final ApplicationSettingsService applicationSettingsService;

    public AdminViewController(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    @GetMapping("/dashboard")
    public String getDashboardPage() {
        return "admin/dashboard";
    }

    @GetMapping("/settings")
    public String getSettingsPage(Model model) {
        ApplicationSettingsEntity settings = applicationSettingsService.getApplicationSettings();

        ApplicationSettingsViewModel applicationSettingsViewModel = new ApplicationSettingsViewModel(settings);
        applicationSettingsViewModel.setMaxFileSize(bytesToMegabytes(settings.getMaxFileSize()));

        model.addAttribute("settings", applicationSettingsViewModel);
        return "admin/settings";
    }

    @PostMapping("/save")
    public String saveSettings(ApplicationSettingsViewModel settings) {
        settings.setMaxFileSize(megabytesToBytes(settings.getMaxFileSize()));


        applicationSettingsService.updateApplicationSettings(settings, settings.getAppPassword());
        return "redirect:/admin/dashboard";
    }
}
