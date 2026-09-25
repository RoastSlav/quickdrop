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
        // Regression check: this route used to be wrongly guarded by AdminPasswordInterceptor.
        // A file-session token succeeding here (no admin-session-token) proves it no longer is.
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "historypw");
        MockHttpSession session = fileSession(file.uuid, "historypw");
        mockMvc.perform(get("/file/history/" + file.uuid).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRoute_bypassesTheInterceptorEvenWithoutSession() throws Exception {
        ensureAdminPasswordSet();
        // /admin/logout bypasses the admin-session check so a stale session can still log out.
        mockMvc.perform(post("/admin/logout").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
