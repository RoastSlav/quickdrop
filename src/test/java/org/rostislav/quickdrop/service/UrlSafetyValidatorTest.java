package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.InetAddress;
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

    // resolvesToOnlyPublicAddresses(): every resolved address is checked, not just the first
    // A host with one public and one private A/AAAA record must not slip past a check that only
    // looked at InetAddress.getByName()'s first result. Literal IPs are resolved locally by the
    // JVM with no real DNS lookup; getAllByName() itself is mocked to return both addresses for
    // one hostname, since no real DNS name is guaranteed to be multi-homed like this.

    @Test
    void resolvesToOnlyPublicAddresses_hostWithOnePrivateAddressAmongOthers_isRejected() throws Exception {
        InetAddress publicAddr = InetAddress.getByName("93.184.216.34");
        InetAddress privateAddr = InetAddress.getByName("10.0.0.5");
        try (MockedStatic<InetAddress> mockedInetAddress = Mockito.mockStatic(InetAddress.class, Mockito.CALLS_REAL_METHODS)) {
            mockedInetAddress.when(() -> InetAddress.getAllByName("multi-homed.example"))
                    .thenReturn(new InetAddress[]{publicAddr, privateAddr});

            assertFalse(validator.resolvesToOnlyPublicAddresses("multi-homed.example"),
                    "one private address among several resolved ones must still reject the host");
        }
    }

    @Test
    void resolvesToOnlyPublicAddresses_hostWithOnlyPublicAddresses_isAccepted() throws Exception {
        InetAddress publicAddr1 = InetAddress.getByName("93.184.216.34");
        InetAddress publicAddr2 = InetAddress.getByName("8.8.8.8");
        try (MockedStatic<InetAddress> mockedInetAddress = Mockito.mockStatic(InetAddress.class, Mockito.CALLS_REAL_METHODS)) {
            mockedInetAddress.when(() -> InetAddress.getAllByName("multi-public.example"))
                    .thenReturn(new InetAddress[]{publicAddr1, publicAddr2});

            assertTrue(validator.resolvesToOnlyPublicAddresses("multi-public.example"));
        }
    }
}
