package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.service.FileService;
import org.rostislav.quickdrop.service.SessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

@Component
public class FilePasswordInterceptor implements HandlerInterceptor {

    private final FileService fileService;
    private final SessionService sessionService;

    public FilePasswordInterceptor(FileService fileService, SessionService sessionService) {
        this.fileService = fileService;
        this.sessionService = sessionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

        //For shared files, no password is required
        if ("GET".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().startsWith("/file/share/")) {
            return true;
        }

        String uuid = pathVariables != null ? pathVariables.get("uuid") : null;

        if (uuid == null || uuid.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "File UUID is missing.");
            return false;
        }

        FileEntity fileEntity = fileService.getFile(uuid);
        if (fileEntity == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found.");
            return false;
        }

        String sessionToken = (String) request.getSession().getAttribute("file-session-token");
        if (fileEntity.passwordHash != null &&
                (sessionToken == null || !sessionService.validateFileSessionToken(sessionToken, uuid))) {

            response.sendRedirect("/file/password/" + uuid);
            return false;
        }

        return true;
    }
}
