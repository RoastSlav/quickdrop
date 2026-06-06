package org.rostislav.quickdrop.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate-limiting interceptor that enforces a sliding-window request limit per
 * remote address and endpoint group.
 *
 * <p>Classified endpoint groups:
 * <ul>
 *   <li>{@code /file/password} → {@code file-password}</li>
 *   <li>{@code /admin/password} → {@code admin-login}</li>
 *   <li>{@code /share/**} → {@code share-download}</li>
 * </ul>
 *
 * <p>Window: 60 seconds, limit: 10 attempts per (remoteAddr, endpointGroup).
 * Exceeding the limit yields HTTP 429 with a {@code Retry-After: 60} header.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int WINDOW_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 10;

    /** Tracks (count, windowStart) per "remoteAddr:endpointGroup" key. */
    private final ConcurrentHashMap<String, RateLimitEntry> entries = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String group = classifyEndpoint(request.getRequestURI());
        if (group == null) {
            return true;
        }

        String key = request.getRemoteAddr() + ":" + group;
        long now = System.currentTimeMillis();
        RateLimitEntry entry = entries.computeIfAbsent(key, k -> new RateLimitEntry(now));

        synchronized (entry) {
            long windowStart = entry.windowStart.get();
            if (now - windowStart > WINDOW_SECONDS * 1000L) {
                entry.count.set(0);
                entry.windowStart.set(now);
            }

            int count = entry.count.incrementAndGet();
            if (count > MAX_ATTEMPTS) {
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
                return false;
            }
        }

        return true;
    }

    /**
     * Classifies the request URI into an endpoint group, or returns {@code null}
     * if the endpoint should not be rate-limited.
     */
    private String classifyEndpoint(String uri) {
        if (uri == null) {
            return null;
        }
        if (uri.equals("/file/password") || uri.startsWith("/file/password/")) {
            return "file-password";
        }
        if (uri.equals("/admin/password") || uri.startsWith("/admin/password/")) {
            return "admin-login";
        }
        if (uri.startsWith("/share/")) {
            return "share-download";
        }
        return null;
    }

    /** Holds the mutable state for a single rate-limit bucket. */
    private static class RateLimitEntry {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicLong windowStart;

        RateLimitEntry(long initialWindowStart) {
            this.windowStart = new AtomicLong(initialWindowStart);
        }
    }
}
