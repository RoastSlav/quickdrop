package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.interceptor.PasswordInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final PasswordInterceptor passwordInterceptor;

    public WebConfig(PasswordInterceptor passwordInterceptor) {
        this.passwordInterceptor = passwordInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(passwordInterceptor)
                .excludePathPatterns("/password/login", "/favicon.ico", "/error");
    }
}
