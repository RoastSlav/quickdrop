package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.populateModelAttributes;

@Controller
@RequestMapping("/file")
public class FileViewController {
    private final FileService fileService;

    public FileViewController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping("/upload")
    public String showUploadFile() {
        return "upload";
    }

    @GetMapping("/list")
    public String listFiles(Model model) {
        List<FileEntity> files = fileService.getFiles();
        model.addAttribute("files", files);
        return "listFiles";
    }

    @GetMapping("/{uuid}")
    public String filePage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(uuid);

        String password = (String) request.getSession().getAttribute("password");
        if (fileEntity.passwordHash != null &&
                (password == null || !fileService.checkPassword(uuid, password))) {
            model.addAttribute("uuid", uuid);
            return "password";
        }

        populateModelAttributes(fileEntity, model, request);

        return "fileView";
    }

    @GetMapping("/password")
    public String passwordPage(Model model) {
        return "password";
    }

    @PostMapping("/password")
    public String checkPassword(String uuid, String password, HttpServletRequest request, Model model) {
        if (fileService.checkPassword(uuid, password)) {
            request.getSession().setAttribute("password", password);
            return "redirect:/file/" + uuid;
        } else {
            model.addAttribute("uuid", uuid);
            return "password";
        }
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(id);

        if (fileEntity.passwordHash != null) {
            String password = (String) request.getSession().getAttribute("password");
            if (password == null || !fileService.checkPassword(fileEntity.uuid, password)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        String password = (String) request.getSession().getAttribute("password");
        return fileService.downloadFile(id, password);
    }

    @PostMapping("/extend/{id}")
    public String extendFile(@PathVariable Long id, Model model, HttpServletRequest request) {
        fileService.extendFile(id);

        FileEntity fileEntity = fileService.getFile(id);
        populateModelAttributes(fileEntity, model, request);
        return "fileView";
    }
}
