package org.rostislav.quickdrop.config;

import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unrecognised JDBC URL parameter is silently ignored, so a dropped or misspelled
 * {@code busy_timeout} would leave no protection and nothing to notice until the next
 * intermittent SQLITE_BUSY failure.
 */
class SqliteBusyTimeoutTest extends QuickdropIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void busyTimeoutIsAppliedToPooledConnections() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(rs.next(), "PRAGMA busy_timeout should return a row");
            int busyTimeoutMillis = rs.getInt(1);
            assertTrue(busyTimeoutMillis >= 1000, "busy_timeout not applied, was: " + busyTimeoutMillis);
        }
    }
}
