package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.rostislav.quickdrop.model.FileSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session management for admin and file-level access tokens.
 *
 * <p>Implements {@link HttpSessionListener} so that tokens stored in the HTTP session
 * are automatically removed from the in-memory maps when the session expires,
 * preventing stale token accumulation.
 *
 * <p>Two separate token stores are maintained:
 * <ul>
 *   <li>{@code adminSessionTokens} — UUIDs issued after a successful admin password check.</li>
 *   <li>{@code fileSessions} — token → {@link FileSession} mappings that bind a token to
 *       the cleartext password and UUID of a password-protected file.</li>
 * </ul>
 * Both maps use {@link ConcurrentHashMap} for thread-safe access under concurrent requests.
 */
@Component
public class SessionService implements HttpSessionListener {
    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    private final Set<String> adminSessionTokens = ConcurrentHashMap.newKeySet();
    private final Map<String, FileSession> fileSessions = new ConcurrentHashMap<>();

    /**
     * Removes admin and file session tokens when their HTTP session is invalidated or expires.
     *
     * @param se the session event
     */
    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        HttpSession session = se.getSession();
        Object adminToken = session.getAttribute("admin-session-token");
        if (adminToken != null) {
            adminSessionTokens.remove(adminToken.toString());
            logger.info("Session destroyed, admin session token invalidated: {}", adminToken);
        }

        Object fileSessionToken = session.getAttribute("file-session-token");
        if (fileSessionToken != null) {
            fileSessions.remove(fileSessionToken.toString());
            logger.info("Session destroyed, file session token invalidated: {}", fileSessionToken);
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
        logger.info("admin session token added: {}", token);
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
        logger.info("file session token added: {}", token);
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
        Object token = request.getSession().getAttribute("admin-session-token");
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
            logger.info("Admin session token invalidated: {}", token);
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
