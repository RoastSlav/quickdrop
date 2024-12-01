package org.rostislav.quickdrop.controller;

import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.AnalyticsDataView;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.*;

@Controller
@RequestMapping("/admin")
public class AdminViewController {
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final FileService fileService;

    public AdminViewController(ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService, FileService fileService) {
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.fileService = fileService;
    }

    @GetMapping("/dashboard")
    public String getDashboardPage(Model model) {
        List<FileEntity> files = fileService.getFiles();

        model.addAttribute("files", files.stream().map(
                file -> new FileEntityView(file, formatFileSize(file.size), analyticsService.getTotalDownloadsByFile(file.id))));

        AnalyticsDataView analytics = analyticsService.getAnalytics();
        model.addAttribute("analytics", analytics);

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
