package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    @PostMapping("/upload")
    public String saveFile(@RequestParam("file") MultipartFile file,
                           @RequestParam("description") String description,
                           @RequestParam(value = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                           Model model, HttpServletRequest request) {
        FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely);
        FileEntity fileEntity = fileService.saveFile(file, fileUploadRequest);

        if (fileEntity != null) {
            return filePage(fileEntity.uuid, model, request);
        }
        return "upload";
    }

    @GetMapping("/list")
    public String listFiles(Model model) {
        List<FileEntity> files = fileService.getFiles();
        model.addAttribute("files", files);
        return "listFiles";
    }

    @GetMapping("/{UUID}")
    public String filePage(@PathVariable String UUID, Model model, HttpServletRequest request) {
        FileEntity file = fileService.getFile(UUID);
        model.addAttribute("file", file);
        String downloadLink = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + "/file/" + file.uuid;
        model.addAttribute("downloadLink", downloadLink);

        return "fileUploaded";
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        return fileService.downloadFile(id);
    }
}
