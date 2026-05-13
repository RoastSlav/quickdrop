package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards {@code /admin/**} and {@code /file/history/*} behind a valid admin session token.
 *
 * <p>An admin session token is placed in the HTTP session after a successful password
 * check at {@code POST /admin/password} and validated in memory by
 * {@link SessionService#validateAdminToken(String)}. The token is invalidated when
 * the session expires or when the user logs out.
 *
 * <p>The {@code /admin/logout} path is explicitly allowed through so that the logout
 * action itself is not blocked.
 */
@Component
public class AdminPasswordInterceptor implements HandlerInterceptor {

    private final SessionService sessionService;

    public AdminPasswordInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Verifies that a valid admin session token is present in the HTTP session.
     * Redirects to {@code /admin/password} if the token is absent or invalid.
     *
     * @param request  the incoming HTTP request
     * @param response the HTTP response
     * @param handler  the matched handler (unused)
     * @return {@code true} to continue the handler chain; {@code false} after redirecting
     * @throws Exception if the redirect fails
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("/admin/logout".equals(request.getRequestURI())) {
            return true;
        }
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
