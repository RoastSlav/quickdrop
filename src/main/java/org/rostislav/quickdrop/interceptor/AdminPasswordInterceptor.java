package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminPasswordInterceptor implements HandlerInterceptor {

    private final SessionService sessionService;

    public AdminPasswordInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Object sessionToken = request.getSession().getAttribute("admin-session-token");


        if (sessionToken == null || sessionToken.toString().isEmpty()) {
            response.sendRedirect("/admin/password");
            return false;
        }

        if (!sessionService.validateAdminToken(sessionToken.toString())) {
            response.sendRedirect("/admin/password");
            return false;
        }

        return true;
    }
}
