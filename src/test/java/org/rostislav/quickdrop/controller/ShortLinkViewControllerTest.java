package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.service.ShortLinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NB: {@code RateLimitInterceptor} does not currently cover {@code /s/**} (added in a later
 * change alongside the general rate-limit rollout), so no request-count ceiling applies here.
 */
class ShortLinkViewControllerTest extends ControllerTestSupport {

    @Autowired
    private ShortLinkService shortLinkService;

    @Test
    void newLinkForm_returns200() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/link/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("link-new"));
    }

    @Test
    @DirtiesContext
    void newLinkForm_shortenerDisabled_redirectsHome() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerEnabled(false));
        mockMvc.perform(get("/link/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DirtiesContext
    void newLinkForm_adminOnly_blocksNonAdminSession() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerAdminOnly(true));
        mockMvc.perform(get("/link/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DirtiesContext
    void newLinkForm_adminOnly_adminSessionStillAllowed() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerAdminOnly(true));
        mockMvc.perform(get("/link/new").session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(view().name("link-new"));
    }

    @Test
    @DirtiesContext
    void resolve_redirectLink_existingLinkStillResolvesAfterShortenerDisabled() throws Exception {
        // Disabling stops minting new links; it must not break links already sent to
        // other people -- matches how shareLinksEnabled already behaves.
        ensureAdminPasswordSet();
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/still-works", null, null, null, false, "127.0.0.1");
        updateSettings(s -> s.setShortenerEnabled(false));

        mockMvc.perform(get("/s/" + link.code))
                .andExpect(status().isOk())
                .andExpect(view().name("short-link-preview"));
    }

    @Test
    @DirtiesContext
    void resolve_interstitialModeAlways_showsInterstitialEvenForAdmin() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerInterstitialMode("ALWAYS"));
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/always-preview", null, null, null, false, "127.0.0.1");

        mockMvc.perform(get("/s/" + link.code).session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(view().name("short-link-preview"));
    }

    @Test
    @DirtiesContext
    void resolve_interstitialModeNever_redirectsImmediatelyForNonAdmin() throws Exception {
        ensureAdminPasswordSet();
        updateSettings(s -> s.setShortenerInterstitialMode("NEVER"));
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/no-preview", null, null, null, false, "127.0.0.1");

        mockMvc.perform(get("/s/" + link.code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://example.com/no-preview"));
    }

    @Test
    @DirtiesContext
    void resolve_domainBlocklist_blocksAnAlreadyCreatedLink() throws Exception {
        // The blocklist must apply on resolve too, not just at creation time -- an admin
        // can blocklist a domain after links to it already exist.
        ensureAdminPasswordSet();
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/will-be-blocked", null, null, null, false, "127.0.0.1");
        updateSettings(s -> {
            s.setShortenerDomainRuleMode("BLOCKLIST");
            s.setShortenerDomainRules("example.com");
        });

        mockMvc.perform(get("/s/" + link.code))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }

    @Test
    void resolve_wrongPrefix_returns404() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/wrong-prefix/anything"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolve_unknownCode_showsInvalidLink() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/s/does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }

    @Test
    void resolve_uploadShareLink_forwardsToShareRoute() throws Exception {
        ensureAdminPasswordSet();
        StoredFile file = createFile("a.txt", "hi".getBytes());
        UploadShareLink token = createShareToken(file, null, null);

        mockMvc.perform(get("/s/" + token.code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/share/" + token.code));
    }

    @Test
    void resolve_redirectLink_nonAdmin_showsInterstitialWithoutConsuming() throws Exception {
        ensureAdminPasswordSet();
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/view-test", null, 1, null, false, "127.0.0.1");

        mockMvc.perform(get("/s/" + link.code))
                .andExpect(status().isOk())
                .andExpect(view().name("short-link-preview"))
                .andExpect(model().attribute("displayUrl", "example.com/view-test"));

        assertEquals(1, shareTokenRepository.findById(link.getId()).orElseThrow().remainingUses,
                "viewing the interstitial must not consume the use budget");
    }

    @Test
    void resolve_redirectLink_adminSession_redirectsImmediatelyAndConsumes() throws Exception {
        ensureAdminPasswordSet();
        MockHttpSession session = adminSession();
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/admin-test", null, 1, null, false, "127.0.0.1");

        mockMvc.perform(get("/s/" + link.code).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://example.com/admin-test"));

        assertEquals(0, shareTokenRepository.findById(link.getId()).orElseThrow().remainingUses);
    }

    @Test
    void confirm_validCode_redirectsAndConsumesUse() throws Exception {
        ensureAdminPasswordSet();
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/go-test", null, null, null, false, "127.0.0.1");

        mockMvc.perform(get("/s/" + link.code + "/go"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://example.com/go-test"));

        assertEquals(1, shareTokenRepository.findById(link.getId()).orElseThrow().useCount);
    }

    @Test
    void confirm_unknownCode_showsInvalidLink() throws Exception {
        ensureAdminPasswordSet();
        mockMvc.perform(get("/s/does-not-exist/go"))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }

    @Test
    void confirm_exhaustedLink_showsInvalidLinkOnSecondVisit() throws Exception {
        ensureAdminPasswordSet();
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/single-use", null, 1, null, false, "127.0.0.1");

        mockMvc.perform(get("/s/" + link.code + "/go")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/s/" + link.code + "/go"))
                .andExpect(status().isOk())
                .andExpect(view().name("invalid-share-link"));
    }
}
