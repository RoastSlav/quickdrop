package org.rostislav.quickdrop.config;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the SSRF filter's actual matching behaviour -- not just that the bean exists,
 * but that it allows external addresses and rejects internal/loopback ones. No live network
 * call or Spring context needed: {@link org.springframework.boot.http.client.InetAddressFilter}
 * matches against already-resolved {@link InetAddress} instances.
 */
class HttpClientConfigTest {

    private final HttpClientConfig config = new HttpClientConfig();

    @Test
    void rejectsLoopbackAddress() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertFalse(config.outboundHttpClientAddressFilter().matches(loopback),
                "loopback addresses must be rejected to prevent SSRF against the app itself");
    }

    @Test
    void rejectsPrivateRfc1918Address() throws UnknownHostException {
        InetAddress privateAddr = InetAddress.getByName("10.0.0.5");
        assertFalse(config.outboundHttpClientAddressFilter().matches(privateAddr),
                "private/internal addresses must be rejected to prevent SSRF against internal services");
    }

    @Test
    void allowsExternalAddress() throws UnknownHostException {
        InetAddress external = InetAddress.getByName("8.8.8.8");
        assertTrue(config.outboundHttpClientAddressFilter().matches(external),
                "routable external addresses must be allowed -- this is not a total network lockdown");
    }
}
