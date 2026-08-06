package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the {@code <html lang>} attribute against the active locale.
 *
 * <p>Every page template previously hardcoded {@code lang="en"}, so pages served in the
 * other seven shipped languages still advertised themselves as English — which is what
 * screen readers and browser translation key off, and is invisible in ordinary manual
 * testing because the visible text translates correctly regardless.
 */
class LocaleRenderingTest extends ControllerTestSupport {

    // Assertions anchor on the opening <html> tag rather than a bare lang="xx" substring:
    // the language picker renders data-lang="en" for every locale, which contains lang="en".

    @Test
    void uploadPage_withGermanLocale_advertisesGermanInHtmlLangAttribute() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/upload").param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"de\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html lang=\"en\""))));
    }

    @Test
    void uploadPage_withoutLocaleParameter_fallsBackToEnglish() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/file/upload"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<html lang=\"en\"")));
    }

    /**
     * Structural guard: a newly added page template that copies the old hardcoded
     * {@code lang="en"} would not fail the rendering tests above (they only cover the upload
     * page), so assert the binding is present in every page template. Fragments under
     * {@code templates/fragments/} are excluded — they are included into a host page and
     * carry no {@code <html>} element of their own.
     */
    @Test
    void everyPageTemplateBindsHtmlLangToTheActiveLocale() throws Exception {
        Resource[] templates = new PathMatchingResourcePatternResolver()
                .getResources("classpath:templates/*.html");
        assertTrue(templates.length > 0, "no page templates found on the classpath");

        List<String> missing = new ArrayList<>();
        for (Resource template : templates) {
            String html = new String(template.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (html.contains("<html") && !html.contains("th:lang=")) {
                missing.add(template.getFilename());
            }
        }

        assertTrue(missing.isEmpty(),
                "these page templates hardcode <html lang> instead of binding it to the active "
                        + "locale (add th:lang=\"${#locale.language}\"): " + missing);
    }
}
