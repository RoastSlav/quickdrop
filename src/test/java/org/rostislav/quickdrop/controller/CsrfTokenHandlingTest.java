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
 * {@code SecurityMockMvcRequestPostProcessors.csrf()} (which injects a valid token directly
 * as a request attribute, short-circuiting the real header/cookie resolution these tests
 * exist to exercise).
 *
 * <p>The app's own {@code CookieCsrfTokenRepository.withHttpOnlyFalse()} stores the token in
 * an {@code XSRF-TOKEN} cookie holding the <b>raw</b> value; Thymeleaf's
 * {@code ${_csrf.token}} renders the <b>masked</b> (XOR-encoded) value into a hidden input.
 * All three frontend JS files ({@code fileView.js}, {@code settings.js},
 * {@code upload/network.js}) now read the masked hidden-input/meta value -- the raw cookie
 * value is deliberately no longer accepted (see the rejection test below), since Security
 * 7's default handler only understands masked values. This was a real behavior change, not
 * a no-op refactor: before the frontend was unified, the raw-cookie path was load-bearing
 * for every chunked upload, and swapping the handler without fixing the JS first would have
 * broken every upload with a silent 403.
 *
 * <p>Class-level {@code @DirtiesContext(BEFORE_CLASS)}: these tests depend on
 * {@code CookieCsrfTokenRepository} actually writing an {@code XSRF-TOKEN} cookie on the
 * initial GET, which was observed to silently stop happening when this class ran in the same
 * cached context after {@code AdminViewControllerTest}'s full suite (reproducible, but the
 * exact interaction wasn't pinned down -- forcing a fresh context sidesteps it rather than
 * leaving an order-dependent flake in the suite).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CsrfTokenHandlingTest extends ControllerTestSupport {

    private static final Pattern MASKED_TOKEN_INPUT = Pattern.compile(
            "name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    /** GETs the upload page (renders a masked {@code _csrf} hidden input) and returns the result. */
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
        // every chunk of every upload -- and it worked, because SecurityConfig carried a
        // custom handler with a raw-token fallback. That fallback is gone; Security 7's
        // default handler expects a masked value, so this must now fail closed (403) rather
        // than silently accept an unmasked token.
        // Use an authenticated admin session rather than a bare one: whether /file/upload
        // renders the form at all (vs. redirecting) depends on the uploadEnabled/
        // uploadAdminOnly settings, which other tests sharing this context can leave toggled
        // between runs. Admin sessions bypass both checks, so this test doesn't depend on
        // ambient settings state left behind by test execution order.
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
        // Mirrors fileView.js/settings.js's getCsrfToken(), and upload/network.js's
        // getCsrfToken() after unification: read the masked value Thymeleaf rendered into
        // the page, send it as the X-XSRF-TOKEN header.
        // Use an authenticated admin session rather than a bare one: whether /file/upload
        // renders the form at all (vs. redirecting) depends on the uploadEnabled/
        // uploadAdminOnly settings, which other tests sharing this context can leave toggled
        // between runs. Admin sessions bypass both checks, so this test doesn't depend on
        // ambient settings state left behind by test execution order.
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
        // Use an authenticated admin session rather than a bare one: whether /file/upload
        // renders the form at all (vs. redirecting) depends on the uploadEnabled/
        // uploadAdminOnly settings, which other tests sharing this context can leave toggled
        // between runs. Admin sessions bypass both checks, so this test doesn't depend on
        // ambient settings state left behind by test execution order.
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
