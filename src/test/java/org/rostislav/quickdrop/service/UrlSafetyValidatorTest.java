package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UrlSafetyValidatorTest {

    private final UrlSafetyValidator validator = new UrlSafetyValidator();

    @Test
    void httpsToPublicHostIsSafe() {
        assertTrue(validator.validate(URI.create("https://example.com/page")).isEmpty());
    }

    @Test
    void javascriptSchemeIsRejected() {
        Optional<String> reason = validator.validate(URI.create("javascript:alert(1)"));
        assertTrue(reason.isPresent());
    }

    @Test
    void dataSchemeIsRejected() {
        assertTrue(validator.validate(URI.create("data:text/html,hi")).isPresent());
    }

    @Test
    void fileSchemeIsRejected() {
        assertTrue(validator.validate(URI.create("file:///etc/passwd")).isPresent());
    }

    @Test
    void embeddedCredentialsAreRejected() {
        Optional<String> reason = validator.validate(URI.create("https://evil.com@trusted.com/"));
        assertTrue(reason.isPresent(), "a userinfo-bearing authority must be rejected even though the human-readable host looks trusted");
    }

    @Test
    void loopbackIpIsRejected() {
        assertTrue(validator.validate(URI.create("http://127.0.0.1/")).isPresent());
    }

    @Test
    void loopbackHostnameIsRejected() {
        assertTrue(validator.validate(URI.create("http://localhost/")).isPresent());
    }

    @Test
    void linkLocalIpIsRejected() {
        assertTrue(validator.validate(URI.create("http://169.254.169.254/")).isPresent(),
                "cloud metadata endpoint must be blocked");
    }

    @Test
    void privateClassAIpIsRejected() {
        assertTrue(validator.validate(URI.create("http://10.0.0.5/")).isPresent());
    }

    @Test
    void privateClassBIpIsRejected() {
        assertTrue(validator.validate(URI.create("http://172.16.0.5/")).isPresent());
    }

    @Test
    void privateClassCIpIsRejected() {
        assertTrue(validator.validate(URI.create("http://192.168.1.1/")).isPresent());
    }

    @Test
    void anyLocalIpIsRejected() {
        assertTrue(validator.validate(URI.create("http://0.0.0.0/")).isPresent());
    }

    @Test
    void ipv6UniqueLocalIsRejected() {
        assertTrue(validator.validate(URI.create("http://[fd00::1]/")).isPresent());
    }

    @Test
    void ipv6LoopbackIsRejected() {
        assertTrue(validator.validate(URI.create("http://[::1]/")).isPresent());
    }

    @Test
    void unresolvableHostIsRejected() {
        assertTrue(validator.validate(URI.create("http://this-host-does-not-exist.invalid/")).isPresent());
    }
}
