package org.rostislav.quickdrop.migration;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 1 §3.5 of the test plan: boots Flyway against a fresh, empty SQLite database (this
 * already happens implicitly for every {@link QuickdropIntegrationTest}-based test, via
 * Spring Boot's Flyway auto-configuration running before the context is ready) and makes one
 * explicit assertion about the resulting {@code flyway_schema_history} table.
 *
 * <p><b>Known gap:</b> the plan's "legacy 1.6.0 DB snapshot migrates cleanly" scenario is
 * intentionally skipped here. There is no real 1.6.0-era database file available in this repo
 * to use as a fixture, and fabricating a synthetic one wouldn't actually validate the migration
 * path against real historical data/schema quirks -- it would just prove the migrations work
 * against whatever guess we encoded into the fixture. If a real pre-2.0 database snapshot
 * becomes available, it should be added at {@code src/test/resources/fixtures/legacy-1.6.db}
 * and a new test should boot Flyway against a copy of it, then assert row counts and that the
 * V28/V31/V32 renamed columns carried values over.
 */
class FlywayMigrationTest extends QuickdropIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void allMigrationsApplyCleanly() throws Exception {
        record MigrationRow(String version, boolean success) {
        }
        List<MigrationRow> rows = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (resultSet.next()) {
                rows.add(new MigrationRow(resultSet.getString("version"), resultSet.getBoolean("success")));
            }
        }

        assertFalse(rows.isEmpty(), "expected at least one applied migration in flyway_schema_history");

        List<MigrationRow> failed = rows.stream().filter(r -> !r.success()).toList();
        assertTrue(failed.isEmpty(), "found failed migrations: " + failed);
    }
}
