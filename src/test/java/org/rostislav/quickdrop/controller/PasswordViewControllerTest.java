package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class PasswordViewControllerTest extends ControllerTestSupport {

    @Test
    void loginPage_returnsAppPasswordView() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/password/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("app-password"));
    }

    @Test
    void adminPasswordPage_returnsAdminPasswordView() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/password/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-password"));
    }
}
