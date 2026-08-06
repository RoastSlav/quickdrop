package org.rostislav.quickdrop.config;

import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSRF hardening for outbound HTTP calls made through Spring's auto-configured HTTP
 * clients (currently: {@code NotificationService}'s Discord webhook {@code RestTemplate}).
 * Declaring this bean is picked up automatically by {@code HttpClientAutoConfiguration}
 * and rejects requests whose resolved address isn't external, before connecting.
 *
 * <p>Defense-in-depth alongside the Discord-domain allowlist in
 * {@code ApplicationSettingsService#updateApplicationSettings} -- that guards against
 * the wrong host being configured; this guards against a permitted host's DNS resolving
 * to an internal address. Does not cover the S3/Azure/WebDAV/SFTP SDK clients, which
 * build their own HTTP clients outside this auto-configuration.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public InetAddressFilter outboundHttpClientAddressFilter() {
        return InetAddressFilter.externalAddresses();
    }
}
