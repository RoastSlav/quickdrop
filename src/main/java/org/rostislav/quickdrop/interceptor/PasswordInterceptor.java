package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PasswordInterceptor implements HandlerInterceptor {
    @Value("${app.enable.password}")
    private Boolean enablePassword;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enablePassword) {
            return true;
        }

        Boolean authenticated = (Boolean) request.getSession().getAttribute("authenticated");

        if (authenticated != null && authenticated) {
            return true;
        }

        response.sendRedirect("/password/login");
        return false;
    }
}
