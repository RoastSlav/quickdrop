package org.rostislav.quickdrop.config;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Locale;

/**
 * Injects shared view-model attributes into every Thymeleaf template.
 *
 * <p>The {@link #addGlobalAttributes(Model, HttpServletRequest)} method runs before
 * every controller handler and populates the model with application settings,
 * session state, and locale information that all templates depend on (navigation
 * visibility, feature flags, app name/logo, etc.).
 *
 * <p>Reads are served from the {@code applicationSettings} cache so there is no
 * extra database round-trip per request.
 */
@ControllerAdvice
public class GlobalControllerAdvice {

    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;

    public GlobalControllerAdvice(ApplicationSettingsService applicationSettingsService, SessionService sessionService) {
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
    }

    /**
     * Adds global model attributes available to every view.
     *
     * <p>Attributes injected:
     * <ul>
     *   <li>Feature flags: {@code isFileListPageEnabled}, {@code isAdminDashboardButtonEnabled},
     *       {@code isEncryptionEnabled}, {@code uploadPasswordEnabled}, {@code isPreviewEnabled},
     *       {@code isMetadataStrippingEnabled}, {@code isSimplifiedShareLinksEnabled},
     *       {@code isShareLinksDisabled}, {@code isPastebinEnabled}</li>
     *   <li>Session state: {@code hasAdminSession}, {@code hasAppSession}</li>
     *   <li>Permission flags: {@code canUseKeepIndefinitely}, {@code canHideFromList},
     *       {@code isKeepIndefinitelyAdminOnly}, {@code isHideFromListAdminOnly}</li>
     *   <li>Branding: {@code appName}, {@code appLogoPath}</li>
     *   <li>Locale: {@code currentLang} (BCP-47 language tag, defaults to "en")</li>
     * </ul>
     *
     * @param model   the Spring MVC model
     * @param request the current HTTP request (used to resolve the admin session)
     */
    @ModelAttribute
    public void addGlobalAttributes(Model model, HttpServletRequest request) {
        boolean hasAdminSession = sessionService.hasValidAdminSession(request);
        boolean hasAppSession = request.getUserPrincipal() != null;
        boolean keepIndefinitelyAdminOnly = applicationSettingsService.isKeepIndefinitelyAdminOnly();
        boolean hideFromListAdminOnly = applicationSettingsService.isHideFromListAdminOnly();

        model.addAttribute("isFileListPageEnabled", applicationSettingsService.isFileListPageEnabled());
        model.addAttribute("isAppPasswordSet", applicationSettingsService.isAppPasswordEnabled());
        model.addAttribute("isAdminDashboardButtonEnabled", applicationSettingsService.isAdminDashboardButtonEnabled());
        model.addAttribute("isEncryptionEnabled", applicationSettingsService.isEncryptionEnabled());
        model.addAttribute("uploadPasswordEnabled", applicationSettingsService.isUploadPasswordEnabled());
        model.addAttribute("isPreviewEnabled", applicationSettingsService.isPreviewEnabled());
        model.addAttribute("isMetadataStrippingEnabled", applicationSettingsService.isMetadataStrippingEnabled());
        model.addAttribute("isSimplifiedShareLinksEnabled", applicationSettingsService.isSimplifiedShareLinksEnabled());
        model.addAttribute("isShareLinksDisabled", applicationSettingsService.isShareLinksDisabled());
        model.addAttribute("isPastebinEnabled", applicationSettingsService.isPastebinEnabled());
        model.addAttribute("appName", applicationSettingsService.getAppName());
        model.addAttribute("appLogoPath", applicationSettingsService.getLogoPath());
        model.addAttribute("hasAdminSession", hasAdminSession);
        model.addAttribute("hasAppSession", hasAppSession);
        model.addAttribute("isKeepIndefinitelyAdminOnly", keepIndefinitelyAdminOnly);
        model.addAttribute("canUseKeepIndefinitely", !keepIndefinitelyAdminOnly || hasAdminSession);
        model.addAttribute("isHideFromListAdminOnly", hideFromListAdminOnly);
        model.addAttribute("canHideFromList", !hideFromListAdminOnly || hasAdminSession);
        Locale activeLocale = LocaleContextHolder.getLocale();
        String currentLang = activeLocale == null || activeLocale.getLanguage() == null || activeLocale.getLanguage().isBlank()
                ? "en"
                : activeLocale.getLanguage();
        model.addAttribute("currentLang", currentLang);
    }
}
