package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.FileSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * In-memory session management for admin and file-level access tokens.
 *
 * <p>Implements {@link HttpSessionListener} to remove tokens when their HTTP session expires.
 *
 * <p>Two separate token stores are maintained:
 * <ul>
 *   <li>{@code adminSessionTokens} — UUIDs issued after a successful admin password check.</li>
 *   <li>{@code fileSessions} — token → {@link FileSession} mappings that bind a token to
 *       the cleartext password and UUID of a password-protected file.</li>
 * </ul>
 */
@Component
public class SessionService implements HttpSessionListener {
    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    private final Set<String> adminSessionTokens = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 1000; // Max 1000 concurrent admin sessions
                }
            })
    );
    private final Map<String, FileSession> fileSessions = Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, FileSession>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FileSession> eldest) {
                    return size() > 10000; // Max 10000 concurrent file sessions
                }
            }
    );

    /**
     * Lazily injected to avoid a startup-time circular dependency with the servlet listener
     * registration. {@code AnalyticsService} is only needed at runtime when a session expires.
     */
    @Autowired
    @Lazy
    private AnalyticsService analyticsService;

    /**
     * Lazily injected for the same reason as {@code analyticsService} above: only needed at
     * runtime when a session is created, not during servlet listener registration.
     */
    @Autowired
    @Lazy
    private ApplicationSettingsService applicationSettingsService;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    /**
     * Applies the currently configured session lifetime to every newly created HTTP session.
     *
     * <p>Read live from {@link ApplicationSettingsService} rather than baked in once at
     * startup, so a change to "Session Lifetime" in the admin settings takes effect for the
     * very next session created, without an app restart. {@code sessionLifetime} is stored in
     * minutes; {@link HttpSession#setMaxInactiveInterval(int)} takes seconds.
     *
     * <p>Sessions already in progress keep whatever interval was set when they were created —
     * only sessions created after the settings change pick up the new value.
     *
     * @param se the session event
     */
    @Override
    public void sessionCreated(HttpSessionEvent se) {
        long lifetimeMinutes = applicationSettingsService.getSessionLifetime();
        se.getSession().setMaxInactiveInterval((int) (lifetimeMinutes * 60));
    }

    /**
     * Removes admin and file session tokens when their HTTP session is invalidated or expires.
     *
     * <p>When the admin token is still present at destruction time the session expired due to
     * inactivity (the explicit logout path removes the attribute before calling
     * {@code session.invalidate()}). In that case an {@link EventType#ADMIN_SESSION_EXPIRE}
     * event is written to the activity log using the IP and user-agent stored at login time.
     *
     * @param se the session event
     */
    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        HttpSession session = se.getSession();
        Object adminToken = session.getAttribute("admin-session-token");
        if (adminToken != null) {
            adminSessionTokens.remove(adminToken.toString());
            String at = adminToken.toString();
            logger.info("Session destroyed, admin session token invalidated (id: {}...)", at.length() > 8 ? at.substring(0, 8) : "***");
            // Token still present → session timed out, not an explicit logout.
            String ip = (String) session.getAttribute("admin-ip");
            String ua = (String) session.getAttribute("admin-ua");
            if (applicationContext.isActive()) {
                try {
                    analyticsService.logEvent(EventType.ADMIN_SESSION_EXPIRE, ip, ua);
                } catch (Exception e) {
                    logger.warn("Failed to log admin session expiry event", e);
                }
            }
        }

        Object fileSessionToken = session.getAttribute("file-session-token");
        if (fileSessionToken != null) {
            fileSessions.remove(fileSessionToken.toString());
            String ft = fileSessionToken.toString();
            logger.info("Session destroyed, file session token invalidated (id: {}...)", ft.length() > 8 ? ft.substring(0, 8) : "***");
        }
    }

    /**
     * Registers a new admin session token.
     *
     * @param token the UUID token to register
     * @return the same token (for chaining with {@code session.setAttribute})
     */
    public String addAdminToken(String token) {
        adminSessionTokens.add(token);
        logger.info("Admin session token added (id: {}...)", token.length() > 8 ? token.substring(0, 8) : "***");
        return token;
    }

    /**
     * Registers a new file session token binding it to a password and file UUID.
     *
     * @param token    the UUID token to register
     * @param password cleartext file access password
     * @param fileUuid UUID of the protected file
     * @return the same token
     */
    public String addFileSessionToken(String token, String password, String fileUuid) {
        fileSessions.put(token, new FileSession(password, fileUuid));
        logger.info("File session token added (id: {}...)", token.length() > 8 ? token.substring(0, 8) : "***");
        return token;
    }

    /**
     * Checks whether a token is a currently registered admin session token.
     *
     * @param string the token string to check
     * @return {@code true} if the token is valid
     */
    public boolean validateAdminToken(String string) {
        return adminSessionTokens.contains(string);
    }

    /**
     * Checks whether the current HTTP request carries a valid admin session token.
     *
     * @param request the HTTP request
     * @return {@code true} if the request has an active admin session
     */
    public boolean hasValidAdminSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        Object token = session.getAttribute("admin-session-token");
        return token != null && validateAdminToken(token.toString());
    }

    /**
     * Invalidates the admin session token stored in the current HTTP session.
     *
     * @param request the HTTP request whose admin session should be invalidated
     */
    public void invalidateAdminSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        Object token = session.getAttribute("admin-session-token");
        if (token != null) {
            adminSessionTokens.remove(token.toString());
            session.removeAttribute("admin-session-token");
            String t = token.toString();
            logger.info("Admin session token invalidated (id: {}...)", t.length() > 8 ? t.substring(0, 8) : "***");
        }
    }

    /**
     * Checks whether a file session token is valid and grants access to the given file.
     *
     * @param sessionToken the token string from the HTTP session
     * @param uuid         the UUID of the file being accessed
     * @return {@code true} if the token exists and is bound to the specified file
     */
    public boolean validateFileSessionToken(String sessionToken, String uuid) {
        FileSession fileSession = fileSessions.get(sessionToken);

        if (fileSession == null) {
            return false;
        }

        return fileSession.getFileUuid().equals(uuid);
    }

    /**
     * Returns the {@link FileSession} associated with a file session token.
     *
     * @param sessionToken the token string
     * @return the file session (containing password and UUID), or {@code null} if not found
     */
    public FileSession getPasswordForFileSessionToken(String sessionToken) {
        return fileSessions.get(sessionToken);
    }
}
