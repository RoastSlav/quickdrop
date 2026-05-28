package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.ActivityLogEntry;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.service.*;
import org.rostislav.quickdrop.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.*;

/**
 * Serves all user-facing file and paste views under {@code /file}.
 *
 * <p>Handles the full lifecycle of files and pastes from the browser:
 * <ul>
 *   <li>Upload page ({@code GET /file/upload})</li>
 *   <li>New/edit paste pages and form submissions ({@code /file/paste/**})</li>
 *   <li>Public file list with search and pagination ({@code GET /file/list})</li>
 *   <li>File detail page with preview type detection ({@code GET /file/{uuid}})</li>
 *   <li>In-browser file preview streaming ({@code GET /file/preview/{uuid}})</li>
 *   <li>Download event logging ({@code POST /file/download/log/{uuid}})</li>
 *   <li>File history ({@code GET /file/history/{uuid}})</li>
 *   <li>Password check and password entry page ({@code /file/password/**})</li>
 *   <li>File download ({@code GET /file/download/{uuid}})</li>
 *   <li>Extend, delete, hide, and keep-indefinitely mutations</li>
 *   <li>Search redirect ({@code GET /file/search})</li>
 * </ul>
 *
 * <p>Delete is permitted only for admin sessions or for sessions that hold a
 * valid file-level session token (password-protected files). Hide and
 * keep-indefinitely mutations respect the corresponding admin-only settings.
 */
@Controller
@RequestMapping("/file")
public class FileViewController {
    private static final Logger logger = LoggerFactory.getLogger(FileViewController.class);
    private final FileService fileService;
    private final PasteService pasteService;
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final SessionService sessionService;

    public FileViewController(FileService fileService, PasteService pasteService, ApplicationSettingsService applicationSettingsService, AnalyticsService analyticsService, SessionService sessionService) {
        this.fileService = fileService;
        this.pasteService = pasteService;
        this.applicationSettingsService = applicationSettingsService;
        this.analyticsService = analyticsService;
        this.sessionService = sessionService;
    }

    @GetMapping("/upload")
    public String showUploadFile(Model model, HttpServletRequest request) {
        boolean isAdmin = sessionService.hasValidAdminSession(request);
        if (!isAdmin && !applicationSettingsService.isUploadEnabled()) {
            return "redirect:/";
        }
        if (!isAdmin && applicationSettingsService.isUploadAdminOnly()) {
            return "redirect:/";
        }
        model.addAttribute("maxFileSize", applicationSettingsService.getFormattedMaxFileSize());
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());
        model.addAttribute("isMetadataStrippingEnabled", applicationSettingsService.isMetadataStrippingEnabled());
        return "upload";
    }

    @GetMapping("/paste/new")
    public String showPastePage(Model model, HttpServletRequest request) {
        if (!applicationSettingsService.isPastebinEnabled() && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/";
        }

        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());
        model.addAttribute("isEditMode", false);
        model.addAttribute("isImmutable", false);
        model.addAttribute("pasteTitle", "");
        model.addAttribute("pasteContent", "");
        model.addAttribute("pasteSyntax", "text");
        model.addAttribute("pasteFormAction", "/file/paste");
        model.addAttribute("pasteCancelUrl", "/file/upload");
        return "pastebin";
    }

    @GetMapping("/paste/edit/{uuid}")
    public String showPasteEditPage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        if (!applicationSettingsService.isPastebinEnabled() && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/";
        }

        Upload fileEntity = fileService.getFile(uuid).orElse(null);
        if (fileEntity == null) {
            return "redirect:/file/list";
        }
        if (!(fileEntity instanceof Paste paste)) {
            return "redirect:/file/" + uuid;
        }

        // Immutable pastes cannot be edited at all
        if (paste.immutable) {
            return "redirect:/file/" + uuid;
        }

        // Require edit authorization (password check for pastes that have a password)
        if (!fileService.isAuthorizedToEdit(uuid, request)) {
            return "redirect:/file/password/" + uuid + "?editMode=true";
        }

        String content = pasteService.getPasteContent(uuid, request);
        if (content == null) {
            return "redirect:/file/" + uuid;
        }

        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());
        model.addAttribute("isEditMode", true);
        model.addAttribute("pasteUuid", uuid);
        model.addAttribute("pasteTitle", fileEntity.name == null ? "" : fileEntity.name.replaceFirst("(?i)\\.(txt|md)$", ""));
        model.addAttribute("pasteContent", content);
        model.addAttribute("pasteSyntax", fileEntity.name != null && fileEntity.name.toLowerCase(Locale.ROOT).endsWith(".md") ? "markdown" : "text");
        model.addAttribute("keepIndefinitely", fileEntity.keepIndefinitely);
        model.addAttribute("isImmutable", false); // edit page only reached when not immutable
        model.addAttribute("pasteFormAction", "/file/paste/edit/" + uuid);
        model.addAttribute("pasteCancelUrl", "/file/" + uuid);
        return "pastebin";
    }

    @PostMapping("/paste")
    public String createPaste(@RequestParam(name = "title", required = false) String title,
                              @RequestParam(name = "content", required = false) String content,
                              @RequestParam(name = "syntax", defaultValue = "markdown") String syntax,
                              @RequestParam(name = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                              @RequestParam(name = "password", required = false) String password,
                              @RequestParam(name = "immutable", defaultValue = "false") boolean immutable,
                              @RequestParam(name = "editOnly", defaultValue = "false") boolean editOnly,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        if (!applicationSettingsService.isPastebinEnabled() && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/";
        }
        if (!applicationSettingsService.isUploadPasswordEnabled() && password != null && !password.isBlank()) {
            redirectAttributes.addFlashAttribute("pasteError", "Upload passwords are disabled.");
            return "redirect:/file/paste/new";
        }

        try {
            Upload created = pasteService.createPaste(title, content, syntax, keepIndefinitely, password, immutable, editOnly, request);
            if (created == null) {
                redirectAttributes.addFlashAttribute("pasteError", "Could not create paste.");
                return "redirect:/file/paste/new";
            }
            return "redirect:/file/" + created.uuid;
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("pasteError", e.getMessage());
            return "redirect:/file/paste/new";
        } catch (IOException e) {
            logger.error("Failed to create paste: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("pasteError", "Could not create paste.");
            return "redirect:/file/paste/new";
        }
    }

    @PostMapping("/paste/edit/{uuid}")
    public String updatePaste(@PathVariable String uuid,
                              @RequestParam(name = "title", required = false) String title,
                              @RequestParam(name = "content", required = false) String content,
                              @RequestParam(name = "syntax", defaultValue = "markdown") String syntax,
                              @RequestParam(name = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                              @RequestParam(name = "immutable", defaultValue = "false") boolean setImmutable,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        if (!applicationSettingsService.isPastebinEnabled() && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/";
        }

        Upload upload = fileService.getFile(uuid).orElse(null);
        if (upload instanceof Paste paste && paste.immutable) {
            return "redirect:/file/" + uuid;
        }
        if (!fileService.isAuthorizedToEdit(uuid, request)) {
            return "redirect:/file/password/" + uuid + "?editMode=true";
        }

        try {
            Paste updated = pasteService.updatePaste(uuid, title, content, syntax, keepIndefinitely, setImmutable, request);
            if (updated == null) {
                redirectAttributes.addFlashAttribute("pasteError", "Could not update paste.");
                return "redirect:/file/paste/edit/" + uuid;
            }
            return "redirect:/file/" + updated.uuid;
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("pasteError", e.getMessage());
            return "redirect:/file/paste/edit/" + uuid;
        } catch (IOException e) {
            logger.error("Failed to update paste {}: {}", uuid, e.getMessage());
            redirectAttributes.addFlashAttribute("pasteError", "Could not update paste.");
            return "redirect:/file/paste/edit/" + uuid;
        }
    }

    @GetMapping("/list")
    public String listFiles(@RequestParam(name = "page", defaultValue = "0") int page,
                            @RequestParam(name = "size", defaultValue = "20") int size,
                            @RequestParam(name = "query", required = false) String query,
                            Model model,
                            HttpServletRequest request) {
        boolean hasAdminSession = sessionService.hasValidAdminSession(request);
        if (!applicationSettingsService.isFileListPageEnabled() && !hasAdminSession) {
            return "redirect:/";
        }

        int pageNumber = clampPage(page);
        int pageSize = clampSize(size);

        Page<FileEntityView> filesPage = fileService.getVisibleFiles(PageRequest.of(pageNumber, pageSize), query)
                .map(f -> new FileEntityView(f, 0L));
        model.addAttribute("filesPage", filesPage);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("pageSize", pageSize);
        return "listFiles";
    }

    @GetMapping("/{uuid}")
    public String filePage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        Upload fileEntity = (Upload) request.getAttribute("fileEntity");
        if (fileEntity == null) {
            fileEntity = fileService.getFile(uuid).orElse(null);
        }
        if (fileEntity == null) {
            logger.info("File not found for UUID: {}", uuid);
            return "redirect:/file/list";
        }

        // Soft-deleted files are only accessible to admins.
        if (fileEntity.deleted && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/file/list";
        }

        model.addAttribute("isDeleted", fileEntity.deleted);
        model.addAttribute("maxFileLifeTime", applicationSettingsService.getMaxFileLifeTime());

        if (fileEntity instanceof Paste paste) {
            // Deleted pastes: admin has already been admitted above; the physical file is gone
            // so skip the password/content checks and show the paste view with empty content.
            if (fileEntity.deleted) {
                populateModelAttributes(fileEntity, model, request);
                model.addAttribute("pasteContent", "");
                model.addAttribute("isMarkdownPaste", false);
                model.addAttribute("isImmutable", paste.immutable);
                model.addAttribute("isEditOnly", paste.editOnly);
                model.addAttribute("isPubliclyAccessible", false);
                return "pasteView";
            }

            if (!fileService.isAuthorizedForFile(uuid, request)) {
                return "redirect:/file/password/" + uuid;
            }
            String pasteContent = pasteService.getPasteContent(uuid, request);
            if (pasteContent == null) {
                return "redirect:/file/password/" + uuid;
            }

            pasteService.logPasteView(uuid, request);
            populateModelAttributes(fileEntity, model, request);
            model.addAttribute("pasteContent", pasteContent);
            model.addAttribute("isMarkdownPaste", fileEntity.name != null && fileEntity.name.toLowerCase(Locale.ROOT).endsWith(".md"));
            model.addAttribute("isImmutable", paste.immutable);
            model.addAttribute("isEditOnly", paste.editOnly);
            // A paste is publicly accessible (no credentials needed) when it has no
            // file-level password (or is edit-only, meaning viewing is always free)
            // AND the app-wide password is not enabled.
            boolean noFileAuth = paste.passwordHash == null || paste.passwordHash.isBlank() || paste.editOnly;
            boolean noAppAuth = !applicationSettingsService.isAppPasswordEnabled();
            model.addAttribute("isPubliclyAccessible", noFileAuth && noAppAuth);
            return "pasteView";
        }

        populateModelAttributes(fileEntity, model, request);

        boolean previewsEnabled = applicationSettingsService.isPreviewEnabled();
        boolean isImage = previewsEnabled && isPreviewableImage(fileEntity);
        boolean isText = previewsEnabled && isPreviewableText(fileEntity);
        boolean isPdf = previewsEnabled && isPreviewablePdf(fileEntity);
        boolean isJson = previewsEnabled && isPreviewableJson(fileEntity);
        boolean isCsv = previewsEnabled && isPreviewableCsvOrTsv(fileEntity);

        String previewType = determinePreviewType(isImage, isPdf, isJson, isCsv, isText);
        long previewLimit = applicationSettingsService.getMaxPreviewSizeBytes();
        boolean requireManualPreview = fileEntity.size > previewLimit;

        model.addAttribute("isPreviewEnabled", previewsEnabled);
        model.addAttribute("isPreviewableImage", isImage);
        model.addAttribute("isPreviewableText", isText);
        model.addAttribute("isPreviewablePdf", isPdf);
        model.addAttribute("isPreviewableJson", isJson);
        model.addAttribute("isPreviewableCsv", isCsv);
        model.addAttribute("previewType", previewType);
        model.addAttribute("previewUrl", String.format("/file/preview/%s", uuid));
        model.addAttribute("requireManualPreview", requireManualPreview);
        model.addAttribute("maxPreviewSizeMB", previewLimit / 1024 / 1024);

        return "fileView";
    }

    @GetMapping("/preview/{uuid}")
    public ResponseEntity<StreamingResponseBody> previewFile(@PathVariable String uuid, HttpServletRequest request,
                                                             @RequestParam(name = "manual", defaultValue = "false") boolean manual) {
        return fileService.previewFile(uuid, request, manual);
    }

    @PostMapping("/download/log/{uuid}")
    public ResponseEntity<Void> logDownload(@PathVariable String uuid, HttpServletRequest request) {
        if (!fileService.isAuthorizedForFile(uuid, request)) {
            return ResponseEntity.status(403).build();
        }
        fileService.logDownload(uuid, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history/{uuid}")
    public String viewFileHistory(@PathVariable String uuid, Model model) {
        Upload fileEntity = fileService.getFile(uuid).orElse(null);
        long totalDownloads = analyticsService.getTotalDownloadsByFile(uuid);
        FileEntityView fileEntityView = new FileEntityView(fileEntity, totalDownloads);

        List<ActivityLogEntry> actionLogs = analyticsService.getHistoryByFile(uuid)
                .stream()
                .map(ActivityLogEntry::new)
                .toList();

        model.addAttribute("file", fileEntityView);
        model.addAttribute("actionLogs", actionLogs);

        return "file-history";
    }


    @PostMapping("/password")
    public String checkPassword(@RequestParam("uuid") String uuid,
                                @RequestParam("password") String password,
                                @RequestParam(name = "editMode", defaultValue = "false") boolean editMode,
                                HttpServletRequest request,
                                RedirectAttributes redirectAttributes) {
        if (fileService.checkFilePassword(uuid, password)) {
            String fileSessionToken = sessionService.addFileSessionToken(UUID.randomUUID().toString(), password, uuid);
            request.getSession().setAttribute("file-session-token", fileSessionToken);
            logger.info("Token has been added to the session for file UUID: {}", uuid);
            // For edit-mode logins redirect directly to the edit page
            return editMode ? "redirect:/file/paste/edit/" + uuid : "redirect:/file/" + uuid;
        } else {
            logger.info("Incorrect password attempt for file UUID: {}", uuid);
            redirectAttributes.addFlashAttribute("passwordError", true);
            return editMode
                    ? "redirect:/file/password/" + uuid + "?editMode=true"
                    : "redirect:/file/password/" + uuid;
        }
    }

    @GetMapping("/password/{uuid}")
    public String passwordPage(@PathVariable String uuid,
                               @RequestParam(name = "editMode", defaultValue = "false") boolean editMode,
                               Model model) {
        model.addAttribute("uuid", uuid);
        model.addAttribute("editMode", editMode);
        fileService.getFile(uuid).ifPresent(f -> model.addAttribute("fileName", f.name));
        return "file-password";
    }

    @GetMapping("/download/{uuid}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String uuid, HttpServletRequest request) {
        return fileService.downloadFile(uuid, request);
    }

    @PostMapping("/extend/{uuid}")
    public String extendFile(@PathVariable String uuid, HttpServletRequest request) {
        Upload file = fileService.getFile(uuid).orElse(null);
        if (file == null || file.deleted) return "redirect:/file/" + uuid;
        fileService.extendFile(uuid, request);
        return "redirect:/file/" + uuid;
    }

    @PostMapping("/delete/{uuid}")
    public String deleteFile(@PathVariable String uuid, HttpServletRequest request) {
        if (!isAuthorizedToDelete(uuid, request)) {
            return "redirect:/file/" + uuid;
        }
        FileService.RequesterInfo info = FileUtils.getRequesterInfo(request);
        if (fileService.deleteFileFromDatabaseAndFileSystem(uuid, info.ipAddress(), info.userAgent())) {
            return "redirect:/file/list";
        } else {
            return "redirect:/file/" + uuid;
        }
    }

    private boolean isAuthorizedToDelete(String uuid, HttpServletRequest request) {
        if (sessionService.hasValidAdminSession(request)) {
            return true;
        }
        Upload fileEntity = (Upload) request.getAttribute("fileEntity");
        if (fileEntity == null) {
            fileEntity = fileService.getFile(uuid).orElse(null);
        }
        if (fileEntity == null || fileEntity.passwordHash == null) {
            return false;
        }
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        return sessionToken != null && sessionService.validateFileSessionToken(sessionToken.toString(), uuid);
    }

    @GetMapping("/search")
    public String searchFiles(@RequestParam String query,
                              @RequestParam(name = "size", defaultValue = "20") int size) {
        if (query == null || query.isBlank()) {
            return "redirect:/file/list";
        }
        int pageSize = clampSize(size);
        String encodedQuery = UriUtils.encodeQueryParam(query, java.nio.charset.StandardCharsets.UTF_8);
        return "redirect:/file/list?query=" + encodedQuery + "&page=0&size=" + pageSize;
    }

    @PostMapping("/keep-indefinitely/{uuid}")
    public String updateKeepIndefinitely(@PathVariable String uuid,
                                         @RequestParam(required = false, defaultValue = "false") boolean keepIndefinitely,
                                         HttpServletRequest request) {
        Upload f = fileService.getFile(uuid).orElse(null);
        if (f != null && f.deleted) return "redirect:/file/" + uuid;
        Upload fileEntity = fileService.updateKeepIndefinitely(uuid, keepIndefinitely, request);
        if (fileEntity != null) {
            logger.info("Updated keep indefinitely for file UUID: {} to {}", uuid, keepIndefinitely);
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }


    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid, HttpServletRequest request) {
        Upload f = fileService.getFile(uuid).orElse(null);
        if (f != null && f.deleted) return "redirect:/file/" + uuid;
        Upload fileEntity = fileService.toggleHidden(uuid, request);
        if (fileEntity != null) {
            logger.info("Updated hidden for file UUID: {} to {}", uuid, fileEntity.hidden);
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }

    private void populateModelAttributes(Upload fileEntity, Model model, HttpServletRequest request) {
        model.addAttribute("file", fileEntity);
        model.addAttribute("fileSize", formatFileSize(fileEntity.size));
        model.addAttribute("downloadLink", getDownloadLink(request, fileEntity));
    }
}
