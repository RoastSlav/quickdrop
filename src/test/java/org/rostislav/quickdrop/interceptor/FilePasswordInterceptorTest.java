package org.rostislav.quickdrop.interceptor;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FilePasswordInterceptorTest extends InterceptorTestSupport {

    @Autowired
    private FilePasswordInterceptor filePasswordInterceptor;

    @Test
    void unknownUuid_returns404NeverRedirectsOr500() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noPasswordFile_passesThroughWithoutAnySession() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("public.txt", "hi".getBytes());
        mockMvc.perform(get("/file/" + file.uuid))
                .andExpect(status().isOk());
    }

    @Test
    void passwordProtectedFile_noSession_redirectsToPasswordPage() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "pw");
        mockMvc.perform(get("/file/" + file.uuid))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + file.uuid));
    }

    @Test
    void passwordProtectedFile_validOwnSession_passesThrough() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("secret.txt", "hi".getBytes(), "pw");
        MockHttpSession session = fileSession(file.uuid, "pw");
        mockMvc.perform(get("/file/" + file.uuid).session(session))
                .andExpect(status().isOk());
    }

    /**
     * A file-session token bound to file A must not unlock file B, even though both
     * carry a syntactically valid token in the same HTTP session.
     */
    @Test
    void passwordProtectedFile_sessionTokenForDifferentFile_isRejected() throws Exception {
        ensureAdminPasswordSet();
        StoredFile fileA = createFile("a.txt", "hi".getBytes(), "pwA");
        StoredFile fileB = createFile("b.txt", "bye".getBytes(), "pwB");
        MockHttpSession sessionForA = fileSession(fileA.uuid, "pwA");

        mockMvc.perform(get("/file/" + fileB.uuid).session(sessionForA))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/file/password/" + fileB.uuid));
    }

    /**
     * No route currently wired through this interceptor omits the {@code {uuid}} path
     * variable (every non-excluded pattern under {@code /file/**} and
     * {@code /api/file/share/**} carries one), so this branch can't be reached through a
     * real request today -- it's a defensive guard against a future route addition, not
     * exercised via {@code mockMvc}. Calling {@code preHandle} directly against the real
     * bean is the only way to exercise it.
     */
    @Test
    void preHandle_noUuidPathVariableResolved_sendsBadRequestAndHaltsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/file/whatever");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean shouldContinue = filePasswordInterceptor.preHandle(request, response, new Object());

        assertFalse(shouldContinue, "the handler chain must not continue when uuid is missing");
        assertEquals(400, response.getStatus());
    }

    @Test
    void excludedRoutes_bypassTheInterceptorEvenWithoutAnyFileContext() throws Exception {
        ensureAdminPasswordSet();
        // /file/upload, /file/list, /file/search etc. are excluded in WebConfig and carry no
        // {uuid} path variable at all -- confirm they're reachable without 400/404 noise.
        mockMvc.perform(get("/file/upload")).andExpect(status().isOk());
        mockMvc.perform(get("/file/list")).andExpect(status().isOk());
    }
}
