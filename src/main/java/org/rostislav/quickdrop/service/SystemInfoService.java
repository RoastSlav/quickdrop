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

    /**
     * Queries the SQLite engine for its version string via {@code SELECT sqlite_version()}.
     *
     * @return the SQLite version (e.g. {@code "3.42.0"}), or {@code "Unknown"} on failure
     */
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

    /**
     * Returns the application version from the {@code app.version} property.
     *
     * @return application version string
     */
    public String getAppVersion() {
        return appVersion;
    }

    /**
     * Returns the JVM version from the {@code java.version} system property.
     *
     * @return Java version string (e.g. {@code "21.0.2"})
     */
    public String getJavaVersion() {
        return System.getProperty("java.version");
    }

    /**
     * Returns a combined OS name and version string.
     *
     * @return OS info (e.g. {@code "Linux (5.15.0-78-generic)"})
     */
    public String getOsInfo() {
        return System.getProperty("os.name") + " (" + System.getProperty("os.version") + ")";
    }

    /**
     * Assembles all system info fields into an {@link AboutInfoView}.
     *
     * @return populated about-info view-model
     */
    public AboutInfoView getAboutInfo() {
        return new AboutInfoView(getAppVersion(), getSqliteVersion(), getJavaVersion(), getOsInfo());
    }
}
