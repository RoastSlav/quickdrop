package org.rostislav.quickdrop;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test proving the "test" profile boots a full, isolated application context:
 * Flyway migrates V1..latest against a fresh per-context SQLite file, the settings row
 * is seeded, and scheduling/caching wire up without touching the real repo db/files/log.
 */
class QuickdropApplicationTests extends QuickdropIntegrationTest {

    @Test
    void contextLoadsWithIsolatedStorage() {
        assertTrue(storageDir.toFile().isDirectory());
    }
}
