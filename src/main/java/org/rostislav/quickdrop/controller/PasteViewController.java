package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.FileQueryService;
import org.rostislav.quickdrop.service.PasteService;
import org.rostislav.quickdrop.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.Locale;

/**
 * Handles paste CRUD routes under {@code /file/paste}.
 *
 * <p>All routes redirect to {@code /} when the pastebin feature is disabled and the
 * request does not carry a valid admin session.
 *
 * <ul>
 *   <li>{@code GET  /file/paste/new}        — new-paste form</li>
 *   <li>{@code GET  /file/paste/edit/{uuid}} — edit-paste form (redirects for immutable pastes)</li>
 *   <li>{@code POST /file/paste}             — create paste, redirect to paste view</li>
 *   <li>{@code POST /file/paste/edit/{uuid}} — update paste, redirect to paste view</li>
 * </ul>
 *
 * <p>The paste detail view is rendered by {@link FileViewController#filePage} to keep
 * file and paste views under the same {@code /file/{uuid}} route.
 */
@Controller
@RequestMapping("/file/paste")
public class PasteViewController {
    private static final Logger logger = LoggerFactory.getLogger(PasteViewController.class);
    private final FileQueryService fileQueryService;
    private final PasteService pasteService;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;

    public PasteViewController(FileQueryService fileQueryService,
                               PasteService pasteService,
                               ApplicationSettingsService applicationSettingsService,
                               SessionService sessionService) {
        this.fileQueryService = fileQueryService;
        this.pasteService = pasteService;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
    }

    @GetMapping("/new")
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

    @GetMapping("/edit/{uuid}")
    public String showPasteEditPage(@PathVariable String uuid, Model model, HttpServletRequest request) {
        if (!applicationSettingsService.isPastebinEnabled() && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/";
        }

        Upload fileEntity = fileQueryService.getFile(uuid).orElse(null);
        if (fileEntity == null) {
            return "redirect:/file/list";
        }
        if (!(fileEntity instanceof Paste paste)) {
            return "redirect:/file/" + uuid;
        }

        if (paste.deleted) {
            return "redirect:/file/" + uuid;
        }
        if (paste.immutable) {
            return "redirect:/file/" + uuid;
        }

        if (!fileQueryService.isAuthorizedToEdit(uuid, request)) {
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
        model.addAttribute("isImmutable", false);
        model.addAttribute("pasteFormAction", "/file/paste/edit/" + uuid);
        model.addAttribute("pasteCancelUrl", "/file/" + uuid);
        return "pastebin";
    }

    @PostMapping
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

    @PostMapping("/edit/{uuid}")
    public String updatePaste(@PathVariable String uuid,
                              @RequestParam(name = "title", required = false) String title,
                              @RequestParam(name = "content", required = false) String content,
                              @RequestParam(name = "syntax", defaultValue = "markdown") String syntax,
                              @RequestParam(name = "keepIndefinitely", defaultValue = "false") boolean keepIndefinitely,
                              @RequestParam(name = "immutable", defaultValue = "false") boolean setImmutable,
                              @RequestParam(name = "password", required = false) String password,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {
        if (!applicationSettingsService.isPastebinEnabled() && !sessionService.hasValidAdminSession(request)) {
            return "redirect:/";
        }

        Upload upload = fileQueryService.getFile(uuid).orElse(null);
        if (upload instanceof Paste paste && (paste.deleted || paste.immutable)) {
            return "redirect:/file/" + uuid;
        }
        if (!fileQueryService.isAuthorizedToEdit(uuid, request)) {
            return "redirect:/file/password/" + uuid + "?editMode=true";
        }
        if (!applicationSettingsService.isUploadPasswordEnabled() && password != null && !password.isBlank()) {
            redirectAttributes.addFlashAttribute("pasteError", "Upload passwords are disabled.");
            return "redirect:/file/paste/edit/" + uuid;
        }

        try {
            Paste updated = pasteService.updatePaste(uuid, title, content, syntax, keepIndefinitely, setImmutable, password, request);
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
}
