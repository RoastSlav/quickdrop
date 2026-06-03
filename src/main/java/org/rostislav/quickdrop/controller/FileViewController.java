package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.ActivityLogEntry;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.RequesterInfo;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.rostislav.quickdrop.util.FileUtils.*;

/**
 * Serves all user-facing file views under {@code /file}.
 *
 * <p>Paste CRUD routes ({@code /file/paste/**}) are handled by {@link PasteViewController}.
 *
 * <p>Delete is permitted only for admin sessions or for sessions that hold a
 * valid file-level session token (password-protected files). Hide and
 * keep-indefinitely mutations respect the corresponding admin-only settings.
 */
@Controller
@RequestMapping("/file")
public class FileViewController {
    private static final Logger logger = LoggerFactory.getLogger(FileViewController.class);
    private final FileQueryService fileQueryService;
    private final FileLifecycleService fileLifecycleService;
    private final FileDownloadService fileDownloadService;
    private final PasteService pasteService;
    private final ApplicationSettingsService applicationSettingsService;
    private final AnalyticsService analyticsService;
    private final SessionService sessionService;

    public FileViewController(FileQueryService fileQueryService,
                              FileLifecycleService fileLifecycleService,
                              FileDownloadService fileDownloadService,
                              PasteService pasteService,
                              ApplicationSettingsService applicationSettingsService,
                              AnalyticsService analyticsService,
                              SessionService sessionService) {
        this.fileQueryService = fileQueryService;
        this.fileLifecycleService = fileLifecycleService;
        this.fileDownloadService = fileDownloadService;
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

        Page<FileEntityView> filesPage = fileQueryService.getVisibleFiles(PageRequest.of(pageNumber, pageSize), query)
                .map(f -> new FileEntityView(f, 0L));
        model.addAttribute("filesPage", filesPage);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("pageSize", pageSize);
        return "listFiles";
    }

    /**
     * Renders the detail page for a file or paste.
     *
     * <p>Pastes and files share this route ({@code /file/{uuid}}) so they get the same
     * URL scheme. The method dispatches to either the {@code pasteView} or {@code fileView}
     * template based on the entity type. Soft-deleted records are only accessible to admin
     * sessions; deleted pastes render with empty content rather than attempting a file read.
     *
     * <p>The {@code fileEntity} request attribute is pre-populated by
     * {@link org.rostislav.quickdrop.interceptor.FilePasswordInterceptor} when the file
     * requires a password and the user has already authenticated.
     */
    @GetMapping("/{uuid}")
    public String filePage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        Upload fileEntity = (Upload) request.getAttribute("fileEntity");
        if (fileEntity == null) {
            fileEntity = fileQueryService.getFile(uuid).orElse(null);
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

            if (!fileQueryService.isAuthorizedForFile(uuid, request)) {
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
        return fileDownloadService.previewFile(uuid, request, manual);
    }

    @PostMapping("/download/log/{uuid}")
    public ResponseEntity<Void> logDownload(@PathVariable String uuid, HttpServletRequest request) {
        if (!fileQueryService.isAuthorizedForFile(uuid, request)) {
            return ResponseEntity.status(403).build();
        }
        fileLifecycleService.logDownload(uuid, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/history/{uuid}")
    public String viewFileHistory(@PathVariable String uuid, Model model) {
        Upload fileEntity = fileQueryService.getFile(uuid).orElse(null);
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


    /**
     * Validates the submitted password and, on success, binds a file-session token to the
     * HTTP session so subsequent requests can access the file without re-entering the password.
     *
     * <p>In edit mode ({@code editMode=true}) a successful login redirects directly to the
     * paste edit page rather than the file detail page.
     */
    @PostMapping("/password")
    public String checkPassword(@RequestParam("uuid") String uuid,
                                @RequestParam("password") String password,
                                @RequestParam(name = "editMode", defaultValue = "false") boolean editMode,
                                HttpServletRequest request,
                                RedirectAttributes redirectAttributes) {
        if (fileQueryService.checkFilePassword(uuid, password)) {
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
        fileQueryService.getFile(uuid).ifPresent(f -> model.addAttribute("fileName", f.name));
        return "file-password";
    }

    @GetMapping("/download/{uuid}")
    public ResponseEntity<StreamingResponseBody> downloadFile(@PathVariable String uuid, HttpServletRequest request) {
        return fileDownloadService.downloadFile(uuid, request);
    }

    @PostMapping("/extend/{uuid}")
    public String extendFile(@PathVariable String uuid, HttpServletRequest request) {
        Upload file = fileQueryService.getFile(uuid).orElse(null);
        if (file == null || file.deleted) return "redirect:/file/" + uuid;
        fileLifecycleService.extendFile(uuid, request);
        return "redirect:/file/" + uuid;
    }

    @PostMapping("/delete/{uuid}")
    public String deleteFile(@PathVariable String uuid, HttpServletRequest request) {
        if (!isAuthorizedToDelete(uuid, request)) {
            return "redirect:/file/" + uuid;
        }
        RequesterInfo info = FileUtils.getRequesterInfo(request);
        if (fileLifecycleService.deleteFileFromDatabaseAndFileSystem(uuid, info.ipAddress(), info.userAgent())) {
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
            fileEntity = fileQueryService.getFile(uuid).orElse(null);
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
        Upload f = fileQueryService.getFile(uuid).orElse(null);
        if (f != null && f.deleted) return "redirect:/file/" + uuid;
        Upload fileEntity = fileLifecycleService.updateKeepIndefinitely(uuid, keepIndefinitely, request);
        if (fileEntity != null) {
            logger.info("Updated keep indefinitely for file UUID: {} to {}", uuid, keepIndefinitely);
            return "redirect:/file/" + fileEntity.uuid;
        }
        return "redirect:/file/list";
    }


    @PostMapping("/toggle-hidden/{uuid}")
    public String toggleHidden(@PathVariable String uuid, HttpServletRequest request) {
        Upload f = fileQueryService.getFile(uuid).orElse(null);
        if (f != null && f.deleted) return "redirect:/file/" + uuid;
        Upload fileEntity = fileLifecycleService.toggleHidden(uuid, request);
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
