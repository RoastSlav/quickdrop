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
 * Asserts every Flyway migration applied cleanly against a fresh SQLite database (this
 * already happens implicitly for every {@link QuickdropIntegrationTest}-based test).
 *
 * <p>Does not cover migrating a real legacy pre-2.0 database snapshot -- none is available
 * in this repo, and a synthetic one would only prove the migrations handle our own guess at
 * the old schema. If a real snapshot becomes available, add it at
 * {@code src/test/resources/fixtures/legacy-1.6.db} and assert the V28/V31/V32 renamed
 * columns carried values over.
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
