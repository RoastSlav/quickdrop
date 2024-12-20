package org.rostislav.quickdrop.service;

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

@Component
public class SessionService implements HttpSessionListener {
    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    private static final Set<String> adminSessionTokens = ConcurrentHashMap.newKeySet();
    private static final Map<String, FileSession> fileSessions = new ConcurrentHashMap<>();

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

    public String addAdminToken(String token) {
        adminSessionTokens.add(token);
        logger.info("admin session token added: {}", token);
        return token;
    }

    public String addFileSessionToken(String token, String password, String fileUuid) {
        fileSessions.put(token, new FileSession(password, fileUuid));
        logger.info("file session token added: {}", token);
        return token;
    }

    public boolean validateAdminToken(String string) {
        return adminSessionTokens.contains(string);
    }

    public boolean validateFileSessionToken(String sessionToken, String uuid) {
        FileSession fileSession = fileSessions.get(sessionToken);

        if (fileSession == null) {
            return false;
        }

        return fileSession.getFileUuid().equals(uuid);
    }

    public FileSession getPasswordForFileSessionToken(String sessionToken) {
        return fileSessions.get(sessionToken);
    }
}
