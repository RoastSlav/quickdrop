package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.interceptor.AdminPasswordInterceptor;
import org.rostislav.quickdrop.interceptor.AdminPasswordSetupInterceptor;
import org.rostislav.quickdrop.interceptor.FilePasswordInterceptor;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
                .excludePathPatterns("/file/upload", "/file/list", "/file/password", "/file/password/**", "/file/history/*");
    }

    @Bean
    public ServletContextInitializer servletContextInitializer() {
        return servletContext -> servletContext.setSessionTimeout((int) applicationSettingsService.getSessionLifetime());
    }
}
