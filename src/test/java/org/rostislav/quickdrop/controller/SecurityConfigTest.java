package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.StoredFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for GHSA-jmp6-gfhm-xxvm: {@code addAllowedOriginPattern("*")} reflects back
 * whatever {@code Origin} a caller sends, which is fine on its own, but combined with
 * {@code allowCredentials(true)} it let any website read an authenticated admin's session data
 * via a credentialed cross-origin request. The default (no {@code quickdrop.cors.allowed-origins}
 * override) config must reflect the origin while forcing credentials off.
 */
class SecurityConfigTest extends ControllerTestSupport {

    @Test
    void defaultWildcardOriginReflectsOriginButRejectsCredentials() throws Exception {
        ensureAdminPasswordSet();

        mockMvc.perform(get("/file/list").header("Origin", "https://evil.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    // -- frame-ancestors: restrictive on admin/login, permissive on public routes ------------
    // No legitimate embedding use case for /admin/** or the app-password login page was ever
    // found (checked git history, README, docs, frontend) -- the app-wide "frame-ancestors *"
    // traces to a single undocumented commit. Public routes keep it: they carry no session
    // state a clickjack could exploit, and are plausibly meant to be embeddable.

    @Test
    void adminRoute_getsRestrictiveFrameAncestors() throws Exception {
        ensureAdminPasswordSet();

        mockMvc.perform(get("/admin/password"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none';"));
    }

    @Test
    void appPasswordLoginRoute_getsRestrictiveFrameAncestors() throws Exception {
        ensureAdminPasswordSet();

        mockMvc.perform(get("/password/login"))
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none';"));
    }

    @Test
    void publicFileListRoute_keepsPermissiveFrameAncestors() throws Exception {
        ensureAdminPasswordSet();

        mockMvc.perform(get("/file/list"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors *;"));
    }

    @Test
    void publicShareRoute_keepsPermissiveFrameAncestors() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        var token = createShareToken(file, null, null);

        mockMvc.perform(get("/share/" + token.code))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors *;"));
    }
}
