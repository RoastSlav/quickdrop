package org.rostislav.quickdrop.config;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.rostislav.quickdrop.service.ApplicationSettingsService;

import java.io.IOException;

/**
 * Makes {@link Request#isSecure()}/{@link Request#getScheme()} report {@code https}, so the
 * session and CSRF cookies' default (unset {@code server.servlet.session.cookie.secure})
 * Secure-flag heuristic marks them Secure behind a TLS-terminating reverse proxy. Two
 * independent, live-read settings each gate this on their own — either is enough:
 * <ul>
 *   <li>{@link ApplicationSettingsService#isTrustedProxyEnabled()} — only when the request also
 *       carries {@code X-Forwarded-Proto: https}. The same trust boundary
 *       {@link org.rostislav.quickdrop.util.FileUtils#getRequesterInfo} already applies to
 *       {@code X-Forwarded-For}: honoured only when an admin has confirmed a real reverse proxy
 *       sits in front, since otherwise a direct caller could spoof the header.</li>
 *   <li>{@link ApplicationSettingsService#isForceSecureCookiesEnabled()} — unconditionally, no
 *       header needed. For a proxy that terminates TLS but doesn't send {@code X-Forwarded-Proto}
 *       — the live, DB-backed equivalent of setting {@code server.servlet.session.cookie.secure}
 *       as a startup property.</li>
 * </ul>
 * Both read fresh on every request, the same live-setting pattern
 * {@link SecurityConfig#appPasswordGate()} already uses, so toggling either takes effect
 * immediately with no restart.
 *
 * <p>Implemented as a Tomcat {@link org.apache.catalina.Valve}, not a {@code jakarta.servlet.Filter},
 * because the session cookie's Secure flag is decided by Tomcat's own connector-level
 * {@link Request} when the response commits — a {@code HttpServletRequestWrapper} only changes
 * what {@code isSecure()} returns to Servlet Filters/DispatcherServlet/controllers, not to
 * Tomcat's own cookie-writing code, so a Filter-based version of this class left the session
 * cookie unaffected while still (correctly) fixing the CSRF cookie and the HSTS header, both
 * decided in Spring code that reads {@code request.isSecure()}. A Context-level Valve runs
 * before the Filter chain and mutates the real underlying request, so every downstream
 * consumer — Tomcat's own included — sees the same corrected value. This mirrors what Tomcat's
 * own {@code RemoteIpValve} does unconditionally; this class only adds the live on/off gates.
 */
public class TrustedProxySecureSchemeValve extends ValveBase {
    private final ApplicationSettingsService applicationSettingsService;

    public TrustedProxySecureSchemeValve(ApplicationSettingsService applicationSettingsService) {
        super(true);
        this.applicationSettingsService = applicationSettingsService;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        boolean forceSecure = applicationSettingsService.isForceSecureCookiesEnabled();
        boolean trustedForwardedHttps = applicationSettingsService.isTrustedProxyEnabled() && isForwardedHttps(request);
        if (forceSecure || trustedForwardedHttps) {
            request.setSecure(true);
            request.getCoyoteRequest().scheme().setString("https");
        }
        getNext().invoke(request, response);
    }

    private boolean isForwardedHttps(Request request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        return proto != null && proto.split(",")[0].trim().equalsIgnoreCase("https");
    }
}
