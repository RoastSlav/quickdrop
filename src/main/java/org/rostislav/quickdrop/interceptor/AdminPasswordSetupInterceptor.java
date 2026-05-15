package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Redirects all requests to the first-time admin setup page until an admin
 * password has been configured.
 *
 * <p>The setup endpoint and static asset paths ({@code /static/}, {@code /css/},
 * {@code /js/}, {@code /images/}) are excluded from redirection.
 */
@Component
public class AdminPasswordSetupInterceptor implements HandlerInterceptor {

    private final ApplicationSettingsService applicationSettingsService;

    public AdminPasswordSetupInterceptor(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    /**
     * Redirects to {@code /admin/setup} if no admin password has been set yet,
     * unless the request is already targeting the setup page or a static resource.
     *
     * @param request  the incoming HTTP request
     * @param response the HTTP response
     * @param handler  the matched handler (unused)
     * @return {@code true} to continue the handler chain; {@code false} after redirecting
     * @throws Exception if the redirect fails
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        if (!applicationSettingsService.isAdminPasswordSet()
                && !requestURI.startsWith("/admin/setup")
                && !requestURI.startsWith("/static/")
                && !requestURI.startsWith("/css/")
                && !requestURI.startsWith("/js/")
                && !requestURI.startsWith("/images/")) {
            response.sendRedirect("/admin/setup");
            return false;
        }
        return true;
    }
}
