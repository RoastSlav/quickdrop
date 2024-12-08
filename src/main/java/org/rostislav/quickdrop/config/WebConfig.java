package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.interceptor.AdminPasswordInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminPasswordInterceptor adminPasswordInterceptor;

    @Autowired
    public WebConfig(AdminPasswordInterceptor adminPasswordInterceptor) {
        this.adminPasswordInterceptor = adminPasswordInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminPasswordInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/admin/setup", "/static/**", "/css/**", "/js/**", "/images/**");
    }
}
