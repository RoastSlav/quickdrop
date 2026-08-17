package org.rostislav.quickdrop.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.interceptor.RateLimitInterceptor;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;

/**
 * Base class for Spring-context integration tests (controller slices, interceptors,
 * end-to-end service flows). Activates the "test" profile — see application-test.properties
 * for the per-context isolated SQLite DB — and redirects the settings row's fileStoragePath
 * to a fresh @TempDir before every test method, since that default ("files", relative to
 * process CWD) is DB-seeded, not a Spring property, and would otherwise write into the real
 * repo files/ directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class QuickdropIntegrationTest {

    static {
        // SQLite's JDBC driver (unlike the app's own startup code) does not create its
        // parent directory -- application-test.properties points every context's DB/log
        // file under here, keyed by a fresh ${random.uuid} per distinct context.
        try {
            Files.createDirectories(Path.of("target", "test-data"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TempDir
    protected Path storageDir;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private ApplicationSettingsRepository applicationSettingsRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @BeforeEach
    void redirectFileStorageToTempDir() {
        ApplicationSettingsEntity settings = applicationSettingsRepository.findById(1L).orElseThrow();
        settings.setFileStoragePath(storageDir.toString());
        applicationSettingsRepository.save(settings);
        var cache = cacheManager.getCache("applicationSettings");
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * The rate limiter is a singleton holding per-(IP, endpoint group) counters, and every
     * MockMvc request in the suite arrives from the same address. Without this, requests
     * accumulate across test methods sharing a context and later tests get 429s unrelated
     * to their own behaviour — which is exactly what happened once the share-download group
     * grew to cover {@code /api/file/download/**} alongside {@code /share/**}.
     *
     * <p>Tests that assert the limiter's <em>own</em> behaviour drive it directly rather
     * than through MockMvc, so clearing here doesn't weaken them.
     */
    @BeforeEach
    void resetRateLimiter() {
        rateLimitInterceptor.resetLimits();
    }
}
