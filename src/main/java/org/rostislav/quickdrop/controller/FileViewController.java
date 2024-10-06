package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.model.FileEntity;
import org.rostislav.quickdrop.model.FileUploadRequest;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.util.FileUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.rostislav.quickdrop.util.FileUtils.getDownloadLink;

@Controller
@RequestMapping("/file")
public class FileViewController {
    private final FileService fileService;

    public FileViewController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public String saveFile(@RequestParam("file") MultipartFile file,
                           @RequestParam("description") String description,
                           @RequestParam(value = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                           Model model, HttpServletRequest request) {
        FileUploadRequest fileUploadRequest = new FileUploadRequest(description, keepIndefinitely);
        FileEntity fileEntity = fileService.saveFile(file, fileUploadRequest);

        if (fileEntity != null) {
            model.addAttribute("downloadLink", getDownloadLink(request, fileEntity));
            model.addAttribute("file", fileEntity);
            return "fileUploaded";
        }
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

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        return fileService.downloadFile(id);
    }

    @PostMapping("/extend/{id}")
    public String extendFile(@PathVariable Long id) {
        fileService.extendFile(id);
        return "redirect:/file/list";
    }
}
