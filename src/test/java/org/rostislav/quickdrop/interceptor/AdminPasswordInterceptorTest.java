package org.rostislav.quickdrop.interceptor;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.mock.web.MockHttpSession;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPasswordInterceptorTest extends InterceptorTestSupport {

    @Test
    void adminRoute_noSession_redirectsToAdminPassword() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void adminRoute_invalidSessionToken_redirectsToAdminPassword() throws Exception {
        ensureAdminPasswordSet();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("admin-session-token", "not-a-real-token");
        mockMvc.perform(get("/admin/dashboard").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/password"));
    }

    @Test
    void adminRoute_validSession_passesThrough() throws Exception {
        MockHttpSession session = adminSession();
        mockMvc.perform(get("/admin/dashboard").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void fileHistoryRoute_notGuardedByAdminInterceptor_regressionCheck() throws Exception {
        // Regression check: this route used to be unconditionally covered by
        // AdminPasswordInterceptor (making it admin-only regardless of the file's own
        // password state), which is exactly the bug FileViewControllerTest's
        // fileHistory_anonymousOnPlainNonPasswordFile_returns200 etc. now cover. Asserting
        // here too so a future accidental re-add of "/file/history/*" to this interceptor's
        // addPathPatterns is caught at the interceptor layer, not just the controller layer.
        ensureAdminPasswordSet();
        StoredFile file = createFile("public.txt", "hi".getBytes());
        mockMvc.perform(get("/file/history/" + file.uuid))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRoute_bypassesTheInterceptorEvenWithoutSession() throws Exception {
        ensureAdminPasswordSet();
        // /admin/logout is explicitly excluded from the admin-session check so a stale/expired
        // session can still complete a logout without bouncing back into a redirect loop.
        mockMvc.perform(post("/admin/logout").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
