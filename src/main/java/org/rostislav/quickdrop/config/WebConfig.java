package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.interceptor.AdminPasswordSetupInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminPasswordSetupInterceptor adminPasswordSetupInterceptor;

    @Autowired
    public WebConfig(AdminPasswordSetupInterceptor adminPasswordSetupInterceptor) {
        this.adminPasswordSetupInterceptor = adminPasswordSetupInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminPasswordSetupInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/setup", "/static/**", "/css/**", "/js/**", "/images/**");
    }
}
