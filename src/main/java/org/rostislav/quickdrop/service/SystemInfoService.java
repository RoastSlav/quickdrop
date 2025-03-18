package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.model.AboutInfoView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class SystemInfoService {

    private final DataSource dataSource;

    @Value("${app.version}")
    private String appVersion;

    public SystemInfoService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

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

    public AboutInfoView getAboutInfo() {
        return new AboutInfoView(getAppVersion(), getSqliteVersion(), getJavaVersion(), getOsInfo());
    }
}
