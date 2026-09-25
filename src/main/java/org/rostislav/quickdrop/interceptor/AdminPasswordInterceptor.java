package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards {@code /admin/**} behind a valid admin session token.
 *
 * <p>The {@code /admin/logout} path bypasses this check.
 */
@Component
public class AdminPasswordInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PASSWORD_PATH = "/admin/password";

    private final SessionService sessionService;

    public AdminPasswordInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("/admin/logout".equals(request.getRequestURI())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            response.sendRedirect(ADMIN_PASSWORD_PATH);
            return false;
        }
        Object sessionToken = session.getAttribute(SessionService.ADMIN_SESSION_TOKEN_ATTR);
        if (sessionToken == null || sessionToken.toString().isEmpty()) {
            response.sendRedirect(ADMIN_PASSWORD_PATH);
            return false;
        }
        if (!sessionService.validateAdminToken(sessionToken.toString())) {
            response.sendRedirect(ADMIN_PASSWORD_PATH);
            return false;
        }
        return true;
    }
}
