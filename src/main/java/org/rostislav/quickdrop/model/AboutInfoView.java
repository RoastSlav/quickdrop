package org.rostislav.quickdrop.model;

/**
 * View-model for the "About" section of the admin settings page.
 *
 * <p>Populated by {@link org.rostislav.quickdrop.service.SystemInfoService#getAboutInfo()}
 * and exposed to the Thymeleaf template as read-only diagnostic information.
 */
public class AboutInfoView {
    private String appVersion;
    private String sqliteVersion;
    private String javaVersion;
    private String osInfo;

    public AboutInfoView() {
    }

    /**
     * @param appVersion    the application version string from {@code app.version} in {@code application.properties}
     * @param sqliteVersion SQLite version string returned by {@code SELECT sqlite_version()}
     * @param javaVersion   value of the {@code java.version} system property
     * @param osInfo        combined OS name and version from system properties
     */
    public AboutInfoView(String appVersion, String sqliteVersion, String javaVersion, String osInfo) {
        this.appVersion = appVersion;
        this.sqliteVersion = sqliteVersion;
        this.javaVersion = javaVersion;
        this.osInfo = osInfo;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getSqliteVersion() {
        return sqliteVersion;
    }

    public void setSqliteVersion(String sqliteVersion) {
        this.sqliteVersion = sqliteVersion;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getOsInfo() {
        return osInfo;
    }

    public void setOsInfo(String osInfo) {
        this.osInfo = osInfo;
    }
}
