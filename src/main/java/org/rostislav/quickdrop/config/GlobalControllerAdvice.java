package org.rostislav.quickdrop.config;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalControllerAdvice {

    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;

    public GlobalControllerAdvice(ApplicationSettingsService applicationSettingsService, SessionService sessionService) {
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
    }

    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpServletRequest request) {
        boolean hasAdminSession = sessionService.hasValidAdminSession(request);
        boolean keepIndefinitelyAdminOnly = applicationSettingsService.isKeepIndefinitelyAdminOnly();
        boolean hideFromListAdminOnly = applicationSettingsService.isHideFromListAdminOnly();

        model.addAttribute("isFileListPageEnabled", applicationSettingsService.isFileListPageEnabled());
        model.addAttribute("isAppPasswordSet", applicationSettingsService.isAppPasswordEnabled());
        model.addAttribute("isAdminDashboardButtonEnabled", applicationSettingsService.isAdminDashboardButtonEnabled());
        model.addAttribute("isEncryptionEnabled", applicationSettingsService.isEncryptionEnabled());
        model.addAttribute("uploadPasswordEnabled", applicationSettingsService.isUploadPasswordEnabled());
        model.addAttribute("isPreviewEnabled", applicationSettingsService.isPreviewEnabled());
        model.addAttribute("isMetadataStrippingEnabled", applicationSettingsService.isMetadataStrippingEnabled());
        model.addAttribute("hasAdminSession", hasAdminSession);
        model.addAttribute("isKeepIndefinitelyAdminOnly", keepIndefinitelyAdminOnly);
        model.addAttribute("canUseKeepIndefinitely", !keepIndefinitelyAdminOnly || hasAdminSession);
        model.addAttribute("isHideFromListAdminOnly", hideFromListAdminOnly);
        model.addAttribute("canHideFromList", !hideFromListAdminOnly || hasAdminSession);
    }
}
