package org.rostislav.quickdrop.config;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.rostislav.quickdrop.service.ApplicationSettingsService;

import java.io.IOException;

/**
 * Makes {@link Request#isSecure()}/{@link Request#getScheme()} reflect an incoming
 * {@code X-Forwarded-Proto: https} header, but only when
 * {@link ApplicationSettingsService#isTrustedProxyEnabled()} is on — read fresh on every
 * request, the same live-setting pattern {@link SecurityConfig#appPasswordGate()} already uses,
 * so toggling it takes effect immediately with no restart.
 *
 * <p>This is the same trust boundary {@link org.rostislav.quickdrop.util.FileUtils#getRequesterInfo}
 * already applies to {@code X-Forwarded-For}: the header is only honoured when an admin has
 * confirmed a real reverse proxy sits in front, because otherwise a direct caller could spoof it.
 *
 * <p>Implemented as a Tomcat {@link org.apache.catalina.Valve}, not a {@code jakarta.servlet.Filter},
 * because the session cookie's Secure flag (when {@code server.servlet.session.cookie.secure} is
 * unset) is decided by Tomcat's own connector-level {@link Request} when the response commits —
 * a {@code HttpServletRequestWrapper} only changes what {@code isSecure()} returns to Servlet
 * Filters/DispatcherServlet/controllers, not to Tomcat's own cookie-writing code, so a
 * Filter-based version of this class left the session cookie unaffected while still (correctly)
 * fixing the CSRF cookie and the HSTS header, both decided in Spring code that reads
 * {@code request.isSecure()}. A Context-level Valve runs before the Filter chain and mutates the
 * real underlying request, so every downstream consumer — Tomcat's own included — sees the same
 * corrected value. This mirrors what Tomcat's own {@code RemoteIpValve} does unconditionally;
 * this class only adds the live on/off gate.
 */
public class TrustedProxySecureSchemeValve extends ValveBase {
    private final ApplicationSettingsService applicationSettingsService;

    public TrustedProxySecureSchemeValve(ApplicationSettingsService applicationSettingsService) {
        super(true);
        this.applicationSettingsService = applicationSettingsService;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        if (applicationSettingsService.isTrustedProxyEnabled() && isForwardedHttps(request)) {
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
