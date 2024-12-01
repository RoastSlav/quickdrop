package org.rostislav.quickdrop.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
public class MultipartConfig {
    private final long ADDITIONAL_REQUEST_SIZE = 1024L * 1024L * 10L; // 10 MB

    @Bean
    @RefreshScope
    public MultipartConfigElement multipartConfigElement(MultipartProperties multipartProperties) {
        MultipartConfigFactory factory = new MultipartConfigFactory();

        factory.setMaxFileSize(DataSize.parse(multipartProperties.getMaxFileSize()));

        DataSize maxRequestSize = DataSize.parse(multipartProperties.getMaxFileSize());
        maxRequestSize = DataSize.ofBytes(maxRequestSize.toBytes() + ADDITIONAL_REQUEST_SIZE);
        factory.setMaxRequestSize(maxRequestSize);

        return factory.createMultipartConfig();
    }
}
