package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.model.FileSession;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link SessionService}'s in-memory admin/file token stores.
 *
 * <p>Uses the real Spring-managed bean (rather than manual instantiation) because
 * {@code SessionService} takes its {@code AnalyticsService} and
 * {@code ConfigurableApplicationContext} collaborators via field injection with no
 * public setters -- easiest to satisfy correctly through the real application context.
 */
class SessionServiceTest extends QuickdropIntegrationTest {

    @Autowired
    private SessionService sessionService;

    @Test
    void adminTokenIssuedIsValidAndTracksSession() {
        String token = UUID.randomUUID().toString();
        sessionService.addAdminToken(token);

        assertTrue(sessionService.validateAdminToken(token));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("admin-session-token", token);
        assertTrue(sessionService.hasValidAdminSession(request));
    }

    @Test
    void unknownAdminTokenIsInvalid() {
        assertFalse(sessionService.validateAdminToken("not-a-real-token"));
    }

    @Test
    void requestWithNoSessionHasNoAdminSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // MockHttpServletRequest.getSession(false) returns null before any session exists.
        assertFalse(sessionService.hasValidAdminSession(request));
    }

    @Test
    void invalidateAdminSessionRemovesTokenAndAttribute() {
        String token = UUID.randomUUID().toString();
        sessionService.addAdminToken(token);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("admin-session-token", token);

        sessionService.invalidateAdminSession(request);

        assertFalse(sessionService.validateAdminToken(token));
        assertNull(request.getSession(false).getAttribute("admin-session-token"));
    }

    @Test
    void fileSessionTokenBindsPasswordAndUuidAndRejectsOtherFiles() {
        String token = UUID.randomUUID().toString();
        sessionService.addFileSessionToken(token, "correct-horse", "file-uuid-1");

        assertTrue(sessionService.validateFileSessionToken(token, "file-uuid-1"));
        assertFalse(sessionService.validateFileSessionToken(token, "file-uuid-2"),
                "a file session token for one file must not authorize access to a different file");

        FileSession session = sessionService.getPasswordForFileSessionToken(token);
        assertNotNull(session);
        assertEquals("correct-horse", session.getPassword());
        assertEquals("file-uuid-1", session.getFileUuid());
    }

    @Test
    void unknownFileSessionTokenIsInvalid() {
        assertFalse(sessionService.validateFileSessionToken("bogus-token", "any-uuid"));
        assertNull(sessionService.getPasswordForFileSessionToken("bogus-token"));
    }

    @Test
    void sessionDestroyedRemovesAdminAndFileTokens() {
        String adminToken = UUID.randomUUID().toString();
        String fileToken = UUID.randomUUID().toString();
        sessionService.addAdminToken(adminToken);
        sessionService.addFileSessionToken(fileToken, "pw", "file-uuid-9");

        MockHttpSession httpSession = new MockHttpSession();
        httpSession.setAttribute("admin-session-token", adminToken);
        httpSession.setAttribute("admin-ip", "127.0.0.1");
        httpSession.setAttribute("admin-ua", "JUnit");
        httpSession.setAttribute("file-session-token", fileToken);

        assertDoesNotThrow(() -> sessionService.sessionDestroyed(new HttpSessionEvent(httpSession)));

        assertFalse(sessionService.validateAdminToken(adminToken));
        assertFalse(sessionService.validateFileSessionToken(fileToken, "file-uuid-9"));
    }

    @Test
    void sessionDestroyedWithNoTokensIsANoOp() {
        MockHttpSession httpSession = new MockHttpSession();
        assertDoesNotThrow(() -> sessionService.sessionDestroyed(new HttpSessionEvent(httpSession)));
    }
}
