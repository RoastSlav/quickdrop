package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class IndexViewControllerTest extends ControllerTestSupport {

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void indexPage_beforeAdminSetup_redirectsToAdminSetup() throws Exception {
        // Needs a pristine "no admin password yet" database. Contexts are cached/shared across
        // test classes and JUnit doesn't guarantee method order, so BEFORE_METHOD forces a fresh
        // context here. AdminPasswordSetupInterceptor gates every route, including "/".
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/setup"));
    }

    @Test
    void indexPage_defaultSettings_redirectsToUpload() throws Exception {
        ensureAdminPasswordSet();
        // Default settings: defaultHomePage="upload", uploadEnabled=true, uploadAdminOnly=false.
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/upload"));
    }

    @Test
    @DirtiesContext
    void indexPage_uploadsDisabled_cascadesToFileList() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setUploadEnabled(false));
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/list"));
    }

    @Test
    @DirtiesContext
    void indexPage_allFeaturesDisabled_showsServiceUnavailable() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> {
            s.setUploadEnabled(false);
            s.setFileListPageEnabled(false);
            s.setPastebinEnabled(false);
        });
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("service-unavailable"));
    }

    @Test
    @DirtiesContext
    void indexPage_defaultHomePagePaste_withPastebinEnabled_redirectsToPasteNew() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> {
            s.setDefaultHomePage("paste");
            s.setPastebinEnabled(true);
        });
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/paste/new"));
    }

    @Test
    @DirtiesContext
    void indexPage_defaultHomePagePaste_withPastebinDisabled_fallsThroughToUpload() throws Exception {
        // "paste" home page requires pastebin to ALSO be enabled -- otherwise cascades
        // past the paste check to the default upload branch (uploads still enabled here).
        ensureAdminPasswordSet();
        updateSettings(s -> {
            s.setDefaultHomePage("paste");
            s.setPastebinEnabled(false);
        });
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/upload"));
    }

    @Test
    @DirtiesContext
    void indexPage_defaultHomePageList_withFileListEnabled_redirectsToFileList() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> {
            s.setDefaultHomePage("list");
            s.setFileListPageEnabled(true);
        });
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/list"));
    }

    @Test
    void errorPage_returnsErrorView() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/error"))
                .andExpect(status().isOk())
                .andExpect(view().name("error"));
    }
}
