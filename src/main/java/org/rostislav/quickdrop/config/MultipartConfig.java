package org.rostislav.quickdrop.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
public class MultipartConfig {
    private final long ADDITIONAL_REQUEST_SIZE = 1024L * 1024L * 10L; // 10 MB
    @Value("${max-upload-file-size}")
    private String maxUploadFileSize;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();

        factory.setMaxFileSize(DataSize.parse(maxUploadFileSize));

        DataSize maxRequestSize = DataSize.parse(maxUploadFileSize);
        maxRequestSize = DataSize.ofBytes(maxRequestSize.toBytes() + ADDITIONAL_REQUEST_SIZE);
        factory.setMaxRequestSize(maxRequestSize);

        return factory.createMultipartConfig();
    }
}
