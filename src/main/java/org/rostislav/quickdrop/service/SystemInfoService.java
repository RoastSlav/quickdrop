package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.AboutInfoView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Collects runtime system information for the admin "About" section.
 *
 * <p>Queries the underlying SQLite database for its version string, and reads
 * Java and OS metadata from system properties. The application version is
 * injected from the {@code app.version} property set in {@code application.properties}.
 */
@Service
public class SystemInfoService {

    private final DataSource dataSource;

    @Value("${app.version}")
    private String appVersion;

    public SystemInfoService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the SQLite version, or {@code "Unknown"} on failure */
    public String getSqliteVersion() {
        String query = "SELECT sqlite_version()";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query);
             ResultSet rs = statement.executeQuery()) {

            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException ignored) {
        }
        return "Unknown";
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getJavaVersion() {
        return System.getProperty("java.version");
    }

    public String getOsInfo() {
        return System.getProperty("os.name") + " (" + System.getProperty("os.version") + ")";
    }

    /** Assembles all system info fields into an {@link AboutInfoView}. */
    public AboutInfoView getAboutInfo() {
        return new AboutInfoView(getAppVersion(), getSqliteVersion(), getJavaVersion(), getOsInfo());
    }
}
