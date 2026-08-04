package org.rostislav.quickdrop.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPasswordSetupInterceptorTest extends InterceptorTestSupport {

    /**
     * Needs a pristine "no admin password yet" database, and JUnit does not run test methods in
     * declaration order (nor guarantee ordering relative to sibling classes sharing this cached
     * context -- see application-test.properties), so both assertions that depend on that
     * precondition are combined into one method with its own {@code @DirtiesContext} rather than
     * split across methods that could interleave with something that sets the password first.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void noAdminPasswordYet_forcesRedirectToSetup_untilPasswordIsSet() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/setup"));

        // Applies to arbitrary routes too, not just /admin/**.
        mockMvc.perform(get("/file/list"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/setup"));

        // The setup page itself must remain reachable.
        mockMvc.perform(get("/admin/setup"))
                .andExpect(status().isOk());

        // Once a password is set, the interceptor stops forcing the redirect.
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/list"))
                .andExpect(status().isOk());
    }
}
