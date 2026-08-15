package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.ShortLink;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link ShortLinkService}'s redirect-link creation and resolution,
 * against the real Spring context so {@link LinkGuard} and {@link ShortCodeService} run
 * unmocked.
 */
class ShortLinkServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private ShortLinkService shortLinkService;
    @Autowired
    private ShortLinkRepository shortLinkRepository;

    @Test
    void createRedirectLinkPersistsNormalizedAbsoluteUrl() {
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/page", null, null, null, false, "127.0.0.1");

        assertEquals("https://example.com/page", link.targetUrl);
        assertNotNull(link.code);
        assertTrue(shortLinkRepository.existsByShareToken(link.code));
    }

    @Test
    void createRedirectLinkRejectsUnsafeDestination() {
        LinkRejectedException ex = assertThrows(LinkRejectedException.class,
                () -> shortLinkService.createRedirectLink("http://127.0.0.1/admin", null, null, null, false, "127.0.0.1"));
        assertEquals("unsafe_destination", ex.getReasonCode());
    }

    @Test
    void createRedirectLinkRejectsInvalidInput() {
        LinkRejectedException ex = assertThrows(LinkRejectedException.class,
                () -> shortLinkService.createRedirectLink("   ", null, null, null, false, "127.0.0.1"));
        assertEquals("invalid_url", ex.getReasonCode());
    }

    @Test
    void createRedirectLinkHonorsCustomAlias() {
        String alias = "my-custom-" + UUID.randomUUID().toString().substring(0, 8);
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com", null, null, alias, true, "127.0.0.1");
        assertEquals(alias, link.code);
        assertTrue(link.createdByAdmin);
    }

    @Test
    void createRedirectLinkRejectsReservedAlias() {
        // createdByAdmin=true here: custom aliases default to admin-only (see
        // createRedirectLinkRejectsCustomAliasForNonAdmin), and both rejection paths share
        // the "invalid_alias" reason code -- passing false would make this test pass for
        // the wrong reason (the admin gate, never reaching the reserved-word check at all).
        LinkRejectedException ex = assertThrows(LinkRejectedException.class,
                () -> shortLinkService.createRedirectLink("example.com", null, null, "admin", true, "127.0.0.1"));
        assertEquals("invalid_alias", ex.getReasonCode());
    }

    @Test
    void createRedirectLinkRejectsDuplicateAlias() {
        String alias = "dup-" + UUID.randomUUID().toString().substring(0, 8);
        shortLinkService.createRedirectLink("example.com/a", null, null, alias, true, "127.0.0.1");
        LinkRejectedException ex = assertThrows(LinkRejectedException.class,
                () -> shortLinkService.createRedirectLink("example.com/b", null, null, alias, true, "127.0.0.1"));
        assertEquals("invalid_alias", ex.getReasonCode());
    }

    @Test
    void createRedirectLinkRejectsCustomAliasForNonAdminByDefault() {
        String alias = "nonadmin-" + UUID.randomUUID().toString().substring(0, 8);
        LinkRejectedException ex = assertThrows(LinkRejectedException.class,
                () -> shortLinkService.createRedirectLink("example.com", null, null, alias, false, "127.0.0.1"));
        assertEquals("invalid_alias", ex.getReasonCode());
    }

    @Test
    void resolveAndRecordVisitReturnsEmptyForUnknownCode() {
        assertTrue(shortLinkService.resolveAndRecordVisit("no-such-code").isEmpty());
    }

    @Test
    void resolveAndRecordVisitReturnsEmptyForExpiredLink() {
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/expired", LocalDate.now().minusDays(1), null, null, false, "127.0.0.1");
        assertTrue(shortLinkService.resolveAndRecordVisit(link.code).isEmpty());
    }

    @Test
    void resolveAndRecordVisitIncrementsUseCountAndDecrementsRemainingUses() {
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/limited", null, 2, null, false, "127.0.0.1");

        Optional<ShortLink> first = shortLinkService.resolveAndRecordVisit(link.code);
        assertTrue(first.isPresent());
        assertEquals(1, first.get().useCount);
        assertEquals(1, first.get().remainingUses);

        Optional<ShortLink> second = shortLinkService.resolveAndRecordVisit(link.code);
        assertTrue(second.isPresent());
        assertEquals(2, second.get().useCount);
        assertEquals(0, second.get().remainingUses);
    }

    @Test
    void resolveAndRecordVisitReturnsEmptyOnceExhausted() {
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/one-use", null, 1, null, false, "127.0.0.1");

        assertTrue(shortLinkService.resolveAndRecordVisit(link.code).isPresent());
        assertTrue(shortLinkService.resolveAndRecordVisit(link.code).isEmpty(),
                "a second visit to a one-use link must be rejected");
    }

    @Test
    void resolveAndRecordVisitDoesNotDecrementUnlimitedLink() {
        RedirectLink link = shortLinkService.createRedirectLink(
                "example.com/unlimited", null, null, null, false, "127.0.0.1");

        Optional<ShortLink> visited = shortLinkService.resolveAndRecordVisit(link.code);
        assertTrue(visited.isPresent());
        assertNull(visited.get().remainingUses);
        assertEquals(1, visited.get().useCount);
    }
}
