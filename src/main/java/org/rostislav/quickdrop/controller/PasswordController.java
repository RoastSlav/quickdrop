package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/password")
public class PasswordController {
    @Value("${app.basic.password}")
    private String appPassword;

    @GetMapping("/login")
    public String passwordPage(Model model) {
        return "password";
    }

    @PostMapping("/login")
    public String processPassword(@RequestParam("password") String password, HttpServletRequest request) {
        if (appPassword.equals(password)) {
            request.getSession().setAttribute("authenticated", true);
            return "redirect:/"; // Redirect to home or the intended page
        } else {
            request.setAttribute("error", "Invalid Password");
            return "password"; // Show the password page with an error message
        }
    }
}
