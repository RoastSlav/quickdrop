package org.rostislav.quickdrop.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Configures the multipart file-upload limits to match the value stored in
 * application settings.
 *
 * <p>The bean is annotated with {@link RefreshScope}.
 * The max request size is set to {@code maxFileSize + 10 MB}.
 */
@Configuration
public class MultipartConfig {
    /**
     * Extra bytes added on top of the max file size to cover form field overhead.
     */
    private final long ADDITIONAL_REQUEST_SIZE = 1024L * 1024L * 10L; // 10 MB

    /**
     * Creates the multipart configuration element with limits read from {@link MultipartProperties}.
     *
     * @param multipartProperties provides the dynamically resolved max file size
     * @return the configured {@link MultipartConfigElement}
     */
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
