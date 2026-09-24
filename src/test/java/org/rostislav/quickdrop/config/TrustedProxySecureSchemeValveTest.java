package org.rostislav.quickdrop.config;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.service.ApplicationSettingsService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit test -- no Spring context, no MockMvc. MockMvc doesn't run requests through a
 * real Tomcat connector at all, so it can never exercise a {@link Valve} regardless of how
 * it's written; this constructs real (if minimal) Tomcat request/response objects instead,
 * which is the only way to test the valve's own logic in isolation. End-to-end behavior
 * (that both the session and CSRF cookies actually come back Secure) is verified via a local
 * run instead -- see the commit message.
 */
class TrustedProxySecureSchemeValveTest {

    private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
    private final CapturingValve next = new CapturingValve();
    private final TrustedProxySecureSchemeValve valve = new TrustedProxySecureSchemeValve(settings);

    {
        valve.setNext(next);
    }

    @Test
    void trustedProxyEnabled_forwardedHttps_marksRequestSecure() throws Exception {
        when(settings.isTrustedProxyEnabled()).thenReturn(true);
        Request request = newRequest();
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");

        valve.invoke(request, newResponse());

        assertTrue(next.invoked);
        assertTrue(request.isSecure());
        assertEquals("https", request.getScheme());
    }

    @Test
    void trustedProxyEnabled_forwardedHttps_noForwardedPort_correctsServerPortTo443() throws Exception {
        // Host header carried no port, so Tomcat's parser already defaulted serverPort to 80
        // (its own http-scheme default at parse time) before this valve ever runs.
        when(settings.isTrustedProxyEnabled()).thenReturn(true);
        Request request = newRequest();
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");
        request.setServerPort(80);

        valve.invoke(request, newResponse());

        assertEquals(443, request.getServerPort());
    }

    @Test
    void trustedProxyEnabled_forwardedHttps_withForwardedPort_honorsForwardedPort() throws Exception {
        when(settings.isTrustedProxyEnabled()).thenReturn(true);
        Request request = newRequest();
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Port").setString("8443");
        request.setServerPort(80);

        valve.invoke(request, newResponse());

        assertEquals(8443, request.getServerPort());
    }

    @Test
    void trustedProxyDisabled_forwardedHttps_requestUnchanged() throws Exception {
        when(settings.isTrustedProxyEnabled()).thenReturn(false);
        Request request = newRequest();
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");
        request.setServerPort(80);

        valve.invoke(request, newResponse());

        assertTrue(next.invoked);
        assertFalse(request.isSecure());
        assertEquals(80, request.getServerPort());
    }

    @Test
    void trustedProxyEnabled_noForwardedProtoHeader_requestUnchanged() throws Exception {
        when(settings.isTrustedProxyEnabled()).thenReturn(true);
        Request request = newRequest();

        valve.invoke(request, newResponse());

        assertTrue(next.invoked);
        assertFalse(request.isSecure());
    }

    @Test
    void trustedProxyEnabled_forwardedProtoHttp_requestUnchanged() throws Exception {
        when(settings.isTrustedProxyEnabled()).thenReturn(true);
        Request request = newRequest();
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("http");

        valve.invoke(request, newResponse());

        assertTrue(next.invoked);
        assertFalse(request.isSecure());
    }

    @Test
    void forceSecureCookiesEnabled_noHeaderNoTrustedProxy_marksRequestSecureAnyway() throws Exception {
        when(settings.isForceSecureCookiesEnabled()).thenReturn(true);
        Request request = newRequest();
        request.setServerPort(80);

        valve.invoke(request, newResponse());

        assertTrue(next.invoked);
        assertTrue(request.isSecure());
        assertEquals("https", request.getScheme());
        assertEquals(443, request.getServerPort());
    }

    @Test
    void forceSecureCookiesDisabled_trustedProxyDisabled_requestUnchanged() throws Exception {
        when(settings.isForceSecureCookiesEnabled()).thenReturn(false);
        when(settings.isTrustedProxyEnabled()).thenReturn(false);
        Request request = newRequest();
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");

        valve.invoke(request, newResponse());

        assertTrue(next.invoked);
        assertFalse(request.isSecure());
    }

    private static Request newRequest() {
        Connector connector = new Connector();
        connector.setService(new org.apache.catalina.core.StandardService());
        return new Request(connector, new org.apache.coyote.Request());
    }

    private static Response newResponse() {
        return new Response(new org.apache.coyote.Response());
    }

    /** Records whether the valve delegated to the next one in the pipeline. */
    private static final class CapturingValve implements Valve {
        boolean invoked;

        @Override
        public void invoke(Request request, Response response) {
            this.invoked = true;
        }

        @Override
        public Valve getNext() {
            return null;
        }

        @Override
        public void setNext(Valve valve) {
        }

        @Override
        public void backgroundProcess() {
        }

        @Override
        public boolean isAsyncSupported() {
            return true;
        }
    }
}
