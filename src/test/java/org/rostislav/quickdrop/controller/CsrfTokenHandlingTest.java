package org.rostislav.quickdrop.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks in the CSRF token contract after unifying the frontend on the masked token value
 * and simplifying {@link org.rostislav.quickdrop.config.SecurityConfig} to Security 7's
 * default {@code XorCsrfTokenRequestAttributeHandler} (masked-only). Deliberately bypasses
 * {@code SecurityMockMvcRequestPostProcessors.csrf()}, which injects a valid token directly
 * as a request attribute and would short-circuit the header/cookie resolution under test.
 *
 * <p>{@code CookieCsrfTokenRepository.withHttpOnlyFalse()} stores the <b>raw</b> token in the
 * {@code XSRF-TOKEN} cookie; Thymeleaf's {@code ${_csrf.token}} renders the <b>masked</b>
 * value into a hidden input. All frontend JS now reads the masked value -- the raw cookie
 * value is deliberately no longer accepted (see the rejection test below). This was a real
 * behavior change: before the frontend was unified, the raw-cookie path was load-bearing for
 * every chunked upload.
 *
 * <p>Class-level {@code @DirtiesContext(BEFORE_CLASS)}: {@code CookieCsrfTokenRepository}
 * writing an {@code XSRF-TOKEN} cookie on the initial GET was observed to silently stop
 * happening when this class ran in the same cached context after
 * {@code AdminViewControllerTest}'s full suite -- forcing a fresh context sidesteps it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CsrfTokenHandlingTest extends ControllerTestSupport {

    private static final Pattern MASKED_TOKEN_INPUT = Pattern.compile(
            "name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    /**
     * GETs the upload page and returns the result. Uses an admin session rather than a bare
     * one: whether /file/upload renders the form at all (vs. redirecting) depends on the
     * uploadEnabled/uploadAdminOnly settings, which other tests sharing this context can leave
     * toggled between runs -- an admin session bypasses both checks.
     */
    private MvcResult loadUploadPage(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/file/upload").session(session)).andReturn();
    }

    private String extractMaskedToken(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher m = MASKED_TOKEN_INPUT.matcher(body);
        assertTrue(m.find(), "expected a rendered _csrf hidden input in the upload page");
        return m.group(1);
    }

    private MockMultipartFile chunkFile() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
    }

    @Test
    void chunkUpload_withRawCookieTokenAsHeader_isNowRejected() throws Exception {
        // Before the frontend was unified, this was upload/network.js's actual behavior on
        // every chunk of every upload, working only because SecurityConfig carried a custom
        // handler with a raw-token fallback. That fallback is gone, so this must fail closed.
        MockHttpSession session = adminSession();
        MvcResult getResult = loadUploadPage(session);
        Cookie xsrfCookie = getResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(xsrfCookie, "expected CookieCsrfTokenRepository to set an XSRF-TOKEN cookie");

        mockMvc.perform(multipart("/api/file/upload-chunk").file(chunkFile())
                        .session(session)
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue())
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void chunkUpload_withMaskedHiddenInputTokenAsHeader_isAccepted() throws Exception {
        // Mirrors getCsrfToken() in fileView.js/settings.js/upload/network.js: read the
        // masked value Thymeleaf rendered into the page, send it as the X-XSRF-TOKEN header.
        MockHttpSession session = adminSession();
        MvcResult getResult = loadUploadPage(session);
        Cookie xsrfCookie = getResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(xsrfCookie, "expected CookieCsrfTokenRepository to set an XSRF-TOKEN cookie");
        String maskedToken = extractMaskedToken(getResult);

        mockMvc.perform(multipart("/api/file/upload-chunk").file(chunkFile())
                        .session(session)
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", maskedToken)
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(result -> assertNotForbidden(result.getResponse().getStatus()));
    }

    @Test
    void formSubmit_withMaskedTokenAsParameter_isAccepted() throws Exception {
        // The plain Thymeleaf form-submit path: masked token sent as the _csrf request
        // parameter (not a header) -- e.g. every non-AJAX <form> in the app.
        MockHttpSession session = adminSession();
        MvcResult getResult = loadUploadPage(session);
        Cookie xsrfCookie = getResult.getResponse().getCookie("XSRF-TOKEN");
        String maskedToken = extractMaskedToken(getResult);

        mockMvc.perform(multipart("/api/file/upload-chunk").file(chunkFile())
                        .session(session)
                        .cookie(xsrfCookie)
                        .param("_csrf", maskedToken)
                        .param("fileName", "a.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1"))
                .andExpect(result -> assertNotForbidden(result.getResponse().getStatus()));
    }

    private static void assertNotForbidden(int status) {
        assertNotEquals(403, status, "request was rejected at the CSRF layer (403) -- expected it to pass "
                + "CSRF validation regardless of what the endpoint itself then does with the request");
    }
}
