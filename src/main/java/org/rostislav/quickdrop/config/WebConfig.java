package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.interceptor.AdminPasswordInterceptor;
import org.rostislav.quickdrop.interceptor.AdminPasswordSetupInterceptor;
import org.rostislav.quickdrop.interceptor.FilePasswordInterceptor;
import org.rostislav.quickdrop.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Spring MVC web configuration: interceptors and resource handlers.
 *
 * <p>Session timeout is applied per-session by {@link org.rostislav.quickdrop.service.SessionService#sessionCreated}
 * rather than here, so a settings change takes effect for the next session created without
 * an app restart.
 *
 * <p>Interceptors are registered in priority order:
 * <ol>
 *   <li>{@link AdminPasswordSetupInterceptor} — redirects every request to the
 *       first-time setup page until an admin password has been configured.</li>
 *   <li>{@link AdminPasswordInterceptor} — protects {@code /admin/**} and
 *       {@code /file/history/*} behind an admin session token.</li>
 *   <li>{@link FilePasswordInterceptor} — enforces per-file password requirements
 *       on the {@code /file/**} routes.</li>
 *   <li>{@link RateLimitInterceptor} — sliding-window request limiting on
 *       password/login endpoints, share-token routes ({@code /share/**} and the
 *       {@code /api/file/download/**} endpoint that streams the bytes), and the
 *       link-shortener's create and resolve routes.</li>
 * </ol>
 *
 * <p>The {@code /branding/**} resource handler maps to the {@code branding/}
 * directory on disk and sets a one-day {@code Cache-Control} header.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminPasswordSetupInterceptor adminPasswordSetupInterceptor;
    private final AdminPasswordInterceptor adminPasswordInterceptor;
    private final FilePasswordInterceptor filePasswordInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Autowired
    public WebConfig(AdminPasswordSetupInterceptor adminPasswordSetupInterceptor, AdminPasswordInterceptor adminPasswordInterceptor, FilePasswordInterceptor filePasswordInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.adminPasswordSetupInterceptor = adminPasswordSetupInterceptor;
        this.adminPasswordInterceptor = adminPasswordInterceptor;
        this.filePasswordInterceptor = filePasswordInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminPasswordSetupInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/setup", "/static/**", "/css/**", "/js/**", "/images/**");

        registry.addInterceptor(adminPasswordInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/password", "/admin/setup");

        // /file/history/* is deliberately excluded from BOTH interceptors below: it's gated
        // by FileViewController#viewFileHistory's own "file session OR admin session" check
        // instead. Gating it by AdminPasswordInterceptor (as before) made it admin-only,
        // unreachable for a caller holding a valid file-session token; gating it by
        // FilePasswordInterceptor instead would block an admin who lacks a file-session-token
        // for that specific file, since that interceptor has no admin bypass. Neither
        // blanket interceptor alone expresses the controller's actual "OR" intent.
        registry.addInterceptor(filePasswordInterceptor)
                .addPathPatterns("/file/**", "/api/file/share/**")
                .excludePathPatterns("/file/upload", "/file/list", "/file/password", "/file/password/**", "/file/history/*", "/file/search", "/file/paste", "/file/paste/new");

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/file/password", "/admin/password", "/share/**",
                        "/api/file/download/**", "/api/link", "/s/**");
    }

    /**
     * Serves files from the {@code branding/} directory under {@code /branding/**} with a
     * one-day public cache header to reduce logo re-downloads on page navigation.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path brandingDir = Path.of("branding").toAbsolutePath();
        registry.addResourceHandler("/branding/**")
                .addResourceLocations("file:" + brandingDir + "/")
                .setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());
    }
}
