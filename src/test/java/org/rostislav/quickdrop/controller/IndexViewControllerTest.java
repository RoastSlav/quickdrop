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
        // Needs a pristine "no admin password yet" database. QuickdropIntegrationTest-based
        // contexts with identical configuration are cached and shared across ALL test classes
        // (see application-test.properties), and JUnit does not run test methods in declaration
        // order even within one class -- so this can't rely on being "the first test that runs"
        // anywhere in the suite. Method-level BEFORE_METHOD forces a brand new context (and thus
        // a freshly Flyway-migrated DB with no admin password set) immediately before this test.
        //
        // AdminPasswordSetupInterceptor forces every route to /admin/setup until an admin
        // password exists -- this applies globally, including "/".
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
