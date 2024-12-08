package org.rostislav.quickdrop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/password")
public class PasswordController {
    @GetMapping("/login")
    public String passwordPage() {
        return "app-password";
    }

    @GetMapping("/admin")
    public String adminPasswordPage() {
        return "admin/admin-password";
    }
}
