package org.rostislav.quickdrop.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RateLimitInterceptor} is a plain POJO (no Spring beans needed) -- exercised directly
 * per the test plan rather than through a {@code @SpringBootTest} context.
 */
class RateLimitInterceptorTest {

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor();

    private MockHttpServletRequest requestFor(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void tenRequestsPass_eleventhIsRateLimited() throws Exception {
        String uri = "/file/password";
        for (int i = 1; i <= 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean allowed = interceptor.preHandle(requestFor(uri, "10.0.0.1"), response, new Object());
            assertTrue(allowed, "request " + i + " should be allowed");
        }

        MockHttpServletResponse eleventh = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(requestFor(uri, "10.0.0.1"), eleventh, new Object());

        assertFalse(allowed, "11th request in the window must be rejected");
        assertEquals(429, eleventh.getStatus());
        assertEquals("60", eleventh.getHeader("Retry-After"));
    }

    @Test
    void groupsAreCountedIndependently() throws Exception {
        String remoteAddr = "10.0.0.2";
        // Exhaust file-password for this address.
        for (int i = 1; i <= 10; i++) {
            interceptor.preHandle(requestFor("/file/password", remoteAddr), new MockHttpServletResponse(), new Object());
        }
        MockHttpServletResponse filePasswordEleventh = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(requestFor("/file/password", remoteAddr), filePasswordEleventh, new Object()));
        assertEquals(429, filePasswordEleventh.getStatus());

        // admin-login and share-download buckets for the SAME address must be unaffected.
        MockHttpServletResponse adminLogin = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(requestFor("/admin/password", remoteAddr), adminLogin, new Object()));

        MockHttpServletResponse shareDownload = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(requestFor("/share/abc123", remoteAddr), shareDownload, new Object()));
    }

    @Test
    void differentRemoteAddressesAreCountedIndependently() throws Exception {
        String uri = "/admin/password";
        for (int i = 1; i <= 10; i++) {
            interceptor.preHandle(requestFor(uri, "10.0.0.3"), new MockHttpServletResponse(), new Object());
        }
        MockHttpServletResponse blockedForFirstAddr = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(requestFor(uri, "10.0.0.3"), blockedForFirstAddr, new Object()));

        MockHttpServletResponse allowedForOtherAddr = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(requestFor(uri, "10.0.0.4"), allowedForOtherAddr, new Object()));
    }

    @Test
    void unclassifiedEndpoint_isNeverRateLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(requestFor("/file/upload", "10.0.0.5"), response, new Object()));
        }
    }

    /**
     * The 60s window reset path is not exercised via a real {@code Thread.sleep(60000)} --
     * impractical for a unit test -- but via reflection to backdate the internal
     * {@code windowStart} timestamp, per the test plan's suggested workaround.
     */
    @Test
    void windowResetsAfterSixtySeconds_viaBackdatedWindowStart() throws Exception {
        String uri = "/file/password";
        String remoteAddr = "10.0.0.6";
        for (int i = 1; i <= 10; i++) {
            interceptor.preHandle(requestFor(uri, remoteAddr), new MockHttpServletResponse(), new Object());
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(requestFor(uri, remoteAddr), blocked, new Object()));

        backdateWindowStart(remoteAddr + ":file-password", 61_000L);

        MockHttpServletResponse afterReset = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(requestFor(uri, remoteAddr), afterReset, new Object()),
                "a new 60s window should reset the counter");
    }

    @SuppressWarnings("unchecked")
    private void backdateWindowStart(String key, long millisAgo) throws Exception {
        Field entriesField = RateLimitInterceptor.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        Map<String, Object> entries = (Map<String, Object>) entriesField.get(interceptor);
        Object entry = entries.get(key);
        assertNotNull(entry, "expected an existing rate-limit bucket for " + key);

        Field windowStartField = entry.getClass().getDeclaredField("windowStart");
        windowStartField.setAccessible(true);
        AtomicLong windowStart = (AtomicLong) windowStartField.get(entry);
        windowStart.set(System.currentTimeMillis() - millisAgo);
    }
}
