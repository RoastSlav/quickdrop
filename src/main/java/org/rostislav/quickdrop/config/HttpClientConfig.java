package org.rostislav.quickdrop.config;

import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSRF hardening for outbound HTTP calls made through Spring's auto-configured HTTP
 * clients (currently: {@code NotificationService}'s Discord webhook {@code RestTemplate}).
 *
 * <p>Declaring an {@link InetAddressFilter} bean here is picked up automatically by
 * {@code HttpClientAutoConfiguration}, which folds it into the shared
 * {@code HttpClientSettings} bean that every auto-configured {@code RestTemplateBuilder}/
 * {@code RestClient.Builder}/{@code WebClient.Builder} is built from. Resolved addresses
 * that don't match the filter cause the request to fail before a connection is attempted.
 *
 * <p>This is defense-in-depth on top of, not a replacement for, the Discord-domain
 * allowlist in {@code ApplicationSettingsService#updateApplicationSettings} -- that
 * guards against the wrong host being configured at all; this guards against a
 * permitted host's DNS resolving to an internal address.
 *
 * <p>Only covers clients built through Spring Boot's HTTP client auto-configuration.
 * The S3, Azure, WebDAV, and SFTP storage backends use their own SDK clients and are
 * unaffected by this filter.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public InetAddressFilter outboundHttpClientAddressFilter() {
        return InetAddressFilter.externalAddresses();
    }
}
