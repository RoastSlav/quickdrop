package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.interceptor.AdminPasswordInterceptor;
import org.rostislav.quickdrop.interceptor.AdminPasswordSetupInterceptor;
import org.rostislav.quickdrop.interceptor.FilePasswordInterceptor;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Spring MVC web configuration: interceptors, session timeout, and resource handlers.
 *
 * <p>Three interceptors are registered in priority order:
 * <ol>
 *   <li>{@link AdminPasswordSetupInterceptor} — redirects every request to the
 *       first-time setup page until an admin password has been configured.</li>
 *   <li>{@link AdminPasswordInterceptor} — protects {@code /admin/**} and
 *       {@code /file/history/*} behind an admin session token.</li>
 *   <li>{@link FilePasswordInterceptor} — enforces per-file password requirements
 *       on the {@code /file/**} routes.</li>
 * </ol>
 *
 * <p>The {@code /branding/**} resource handler maps to the {@code branding/}
 * directory on disk and sets a one-day {@code Cache-Control} header.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminPasswordSetupInterceptor adminPasswordSetupInterceptor;
    private final AdminPasswordInterceptor adminPasswordInterceptor;
    private final ApplicationSettingsService applicationSettingsService;
    private final FilePasswordInterceptor filePasswordInterceptor;

    @Autowired
    public WebConfig(AdminPasswordSetupInterceptor adminPasswordSetupInterceptor, AdminPasswordInterceptor adminPasswordInterceptor, ApplicationSettingsService applicationSettingsService, FilePasswordInterceptor filePasswordInterceptor) {
        this.adminPasswordSetupInterceptor = adminPasswordSetupInterceptor;
        this.adminPasswordInterceptor = adminPasswordInterceptor;
        this.applicationSettingsService = applicationSettingsService;
        this.filePasswordInterceptor = filePasswordInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminPasswordSetupInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/setup", "/static/**", "/css/**", "/js/**", "/images/**");

        registry.addInterceptor(adminPasswordInterceptor)
                .addPathPatterns("/admin/**", "/file/history/*")
                .excludePathPatterns("/admin/password", "/admin/setup");

        registry.addInterceptor(filePasswordInterceptor)
                .addPathPatterns("/file/**", "/api/file/share/**")
                .excludePathPatterns("/file/upload", "/file/list", "/file/password", "/file/password/**", "/file/history/*", "/file/search", "/file/paste", "/file/paste/new");
    }

    /**
     * Applies the session timeout from application settings.
     *
     * @return a {@link ServletContextInitializer} that sets the session timeout
     */
    @Bean
    public ServletContextInitializer servletContextInitializer() {
        return servletContext -> servletContext.setSessionTimeout((int) applicationSettingsService.getSessionLifetime());
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
