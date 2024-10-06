package org.rostislav.quickdrop.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class IndexViewController {

    @GetMapping("/")
    public RedirectView index() {
        return new RedirectView("/file/upload");
    }
}
