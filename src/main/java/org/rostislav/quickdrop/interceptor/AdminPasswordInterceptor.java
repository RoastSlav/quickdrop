package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminPasswordInterceptor implements HandlerInterceptor {

    private final ApplicationSettingsService applicationSettingsService;

    public AdminPasswordInterceptor(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

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
