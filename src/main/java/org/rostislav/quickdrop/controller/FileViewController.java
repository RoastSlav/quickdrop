package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.populateModelAttributes;

@Controller
@RequestMapping("/file")
public class FileViewController {
    private final FileService fileService;
    private final ApplicationSettingsService applicationSettingsService;
    @Value("${file.max.age}")
    private String maxFileLifeTime;

    public FileViewController(FileService fileService, ApplicationSettingsService applicationSettingsService) {
        this.fileService = fileService;
        this.applicationSettingsService = applicationSettingsService;
    }

    @GetMapping("/upload")
    public String showUploadFile(Model model) {
        model.addAttribute("maxFileSize", applicationSettingsService.getFormattedMaxFileSize());
        model.addAttribute("maxFileLifeTime", maxFileLifeTime);
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
        model.addAttribute("maxFileLifeTime", maxFileLifeTime);

        String password = (String) request.getSession().getAttribute("password");
        if (fileEntity.passwordHash != null &&
                (password == null || !fileService.checkPassword(uuid, password))) {
            model.addAttribute("uuid", uuid);
            return "filePassword";
        }

        populateModelAttributes(fileEntity, model, request);

        return "fileView";
    }

    @PostMapping("/password")
    public String checkPassword(String uuid, String password, HttpServletRequest request, Model model) {
        if (fileService.checkPassword(uuid, password)) {
            request.getSession().setAttribute("password", password);
            return "redirect:/file/" + uuid;
        } else {
            model.addAttribute("uuid", uuid);
            return "filePassword";
        }
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable Long id, HttpServletRequest request) {
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

    @PostMapping("/delete/{id}")
    public String deleteFile(@PathVariable Long id) {
        if (fileService.deleteFile(id)) {
            return "redirect:/file/list";
        } else {
            return "redirect:/file/" + id;
        }
    }

    @GetMapping("/search")
    public String searchFiles(String query, Model model) {
        List<FileEntity> files = fileService.searchFiles(query);
        model.addAttribute("files", files);
        return "listFiles";
    }
}
