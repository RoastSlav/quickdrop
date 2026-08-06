package org.rostislav.quickdrop.config;

import jakarta.servlet.MultipartConfigElement;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
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
    /**
     * Extra bytes added on top of the max file size to cover form field overhead.
     */
    private static final long ADDITIONAL_REQUEST_SIZE = 1024L * 1024L * 10L; // 10 MB

    /**
     * Creates the multipart configuration element with limits read live from
     * {@link ApplicationSettingsService} on every call.
     *
     * @param settings provides the dynamically resolved max file size
     * @return the configured {@link MultipartConfigElement}
     */
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
     * Raises Tomcat's {@code maxPartCount} connector limit (default 50) so that
     * large multipart forms – such as the admin settings form with 100+ fields –
     * are not rejected with {@code FileCountLimitExceededException}.
     *
     * <p>Tomcat's {@code Connector.maxPartCount} caps the file-part count allowed
     * per multipart request, independent of {@code max-parameter-count}.
     * The admin settings form sends 100+ multipart parts (one per field) when a
     * logo file is attached, which exceeds the 50-part default.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatMultipartPartCountCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.setMaxPartCount(500));
    }
}
