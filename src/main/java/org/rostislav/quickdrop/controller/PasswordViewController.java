package org.rostislav.quickdrop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Renders static password entry pages.
 *
 * <p>Both endpoints simply return template names; the actual credential validation
 * is handled by {@link org.rostislav.quickdrop.interceptor.AdminPasswordInterceptor}
 * and by the form POST handlers in {@link AdminViewController} and
 * {@link FileViewController}.
 */
@Controller
@RequestMapping("/password")
public class PasswordViewController {

    /**
     * @return the application-level access password page
     */
    @GetMapping("/login")
    public String passwordPage() {
        return "app-password";
    }

    /** @return the admin password entry page */
    @GetMapping("/admin")
    public String adminPasswordPage() {
        return "admin/admin-password";
    }
}
