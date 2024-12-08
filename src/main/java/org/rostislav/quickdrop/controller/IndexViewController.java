package org.rostislav.quickdrop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexViewController {
    @GetMapping("/")
    public String getIndexPage() {
        return "redirect:/file/upload";
    }
}
