package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.util.FileUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.getDownloadLink;

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
        model.addAttribute("file", fileEntity);
        model.addAttribute("downloadLink", getDownloadLink(request, fileEntity));
        model.addAttribute("fileSize", FileUtils.formatFileSize(fileEntity.size));

        return "fileView";
    }

    @GetMapping("/uploaded/{uuid}")
    public String uploadedFile(@PathVariable String uuid, Model model, HttpServletRequest request) {
        FileEntity fileEntity = fileService.getFile(uuid);
        model.addAttribute("file", fileEntity);
        model.addAttribute("fileSize", FileUtils.formatFileSize(fileEntity.size));
        model.addAttribute("downloadLink", getDownloadLink(request, fileEntity));

        return "fileUploaded";
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        return fileService.downloadFile(id);
    }

    @PostMapping("/extend/{id}")
    public String extendFile(@PathVariable Long id, Model model) {
        fileService.extendFile(id);

        model.addAttribute("file", fileService.getFile(id));
        return "fileView";
    }
}
