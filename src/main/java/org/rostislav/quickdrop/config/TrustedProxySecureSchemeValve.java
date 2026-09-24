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
 *
 * <p>Also corrects {@code getServerPort()}, or every server-generated absolute redirect breaks
 * behind a proxy. When the inbound {@code Host} header carries no explicit port (the normal case
 * — the client connected on its scheme's default port, so its browser omits it), Tomcat's own
 * request parser defaults {@code serverPort} from the scheme it sees <em>at parse time</em> —
 * still plain {@code http} here, since that's genuinely what the embedded connector is — landing
 * on {@code 80}, not this app's real listening port. That happens before this valve ever runs, so
 * once the scheme above flips to {@code https}, {@code getServerPort()} is left stuck at the
 * wrong default: any code that builds an absolute URL from the request (notably Tomcat's own
 * {@code Response.toAbsolute()}, which every relative {@code redirect:} view goes through) then
 * emits {@code https://host:80/...} — a URL a browser will actually attempt a TLS handshake
 * against, and fail. Same fix {@link org.rostislav.quickdrop.util.FileUtils#getBaseUrl} already
 * applies for share-link/QR URLs: honor {@code X-Forwarded-Port} when the proxy sends it,
 * otherwise assume the scheme's standard port, since that's true for effectively every
 * reverse-proxy deployment.
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
            request.setServerPort(resolveForwardedPort(request));
        }
        getNext().invoke(request, response);
    }

    private boolean isForwardedHttps(Request request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        return proto != null && proto.split(",")[0].trim().equalsIgnoreCase("https");
    }

    private int resolveForwardedPort(Request request) {
        String forwardedPort = request.getHeader("X-Forwarded-Port");
        if (forwardedPort != null) {
            try {
                return Integer.parseInt(forwardedPort.split(",")[0].trim());
            } catch (NumberFormatException e) {
                // Fall through to the scheme default below.
            }
        }
        return 443;
    }
}
