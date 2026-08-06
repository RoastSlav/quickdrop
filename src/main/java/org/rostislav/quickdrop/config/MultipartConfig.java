package org.rostislav.quickdrop.config;

import jakarta.servlet.MultipartConfigElement;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the multipart file-upload limits to match the value stored in
 * application settings.
 *
 * <p>{@code getMaxFileSize()}/{@code getMaxRequestSize()} are overridden to read
 * {@link ApplicationSettingsService} live -- Tomcat calls these per request during part
 * parsing, so a settings change takes effect on the very next upload with no bean
 * recreation or app restart needed. The max request size is {@code maxFileSize + 10 MB}.
 */
@Configuration
public class MultipartConfig {
    /** Extra bytes added on top of the max file size to cover form field overhead. */
    private static final long ADDITIONAL_REQUEST_SIZE = 1024L * 1024L * 10L;

    @Bean
    public MultipartConfigElement multipartConfigElement(ApplicationSettingsService settings) {
        return new MultipartConfigElement(null, -1L, -1L, 0) {
            @Override
            public long getMaxFileSize() {
                return settings.getMaxFileSize();
            }

            @Override
            public long getMaxRequestSize() {
                return settings.getMaxFileSize() + ADDITIONAL_REQUEST_SIZE;
            }
        };
    }

    /**
     * Raises Tomcat's {@code maxPartCount} (default 50) so the 100+-field admin settings
     * form isn't rejected with {@code FileCountLimitExceededException} when a logo is attached.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatMultipartPartCountCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.setMaxPartCount(500));
    }
}
