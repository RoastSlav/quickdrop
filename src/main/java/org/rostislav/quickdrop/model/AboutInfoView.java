package org.rostislav.quickdrop.model;

public class AboutInfoView {
    private String appVersion;
    private String sqliteVersion;
    private String javaVersion;
    private String osInfo;

    public AboutInfoView() {
    }

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
