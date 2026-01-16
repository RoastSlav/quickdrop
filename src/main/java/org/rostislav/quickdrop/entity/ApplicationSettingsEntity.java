package org.rostislav.quickdrop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;

@Entity
public class ApplicationSettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long maxFileSize;
    private long maxFileLifeTime;
    private String fileStoragePath;
    private String logStoragePath;
    private String fileDeletionCron;
    private boolean appPasswordEnabled;
    private String appPasswordHash;
    private String adminPasswordHash;
    private long sessionLifetime;
    private boolean isFileListPageEnabled;
    private boolean isAdminDashboardButtonEnabled;
    private boolean disableEncryption;
    private boolean disableUploadPassword;
    private boolean disablePreview;
    private boolean metadataStrippingEnabled;
    private long maxPreviewSizeBytes;
    private String defaultHomePage;
    private boolean keepIndefinitelyAdminOnly;
    private boolean hideFromListAdminOnly;
    private boolean discordWebhookEnabled;
    private String discordWebhookUrl;
    private boolean emailNotificationsEnabled;
    private String emailFrom;
    private String emailTo;
    private String smtpHost;
    private Integer smtpPort;
    private String smtpUsername;
    private String smtpPassword;
    private boolean smtpUseTls;
    private boolean notificationBatchEnabled;
    private Integer notificationBatchMinutes;
    private boolean simplifiedShareLinks;
    private boolean shareLinksDisabled;
    private String appName;
    private String logoFileName;

    public ApplicationSettingsEntity() {
    }

    public ApplicationSettingsEntity(ApplicationSettingsViewModel settings) {
        this.id = settings.getId();
        this.maxFileSize = settings.getMaxFileSize();
        this.maxFileLifeTime = settings.getMaxFileLifeTime();
        this.fileStoragePath = settings.getFileStoragePath();
        this.logStoragePath = settings.getLogStoragePath();
        this.fileDeletionCron = settings.getFileDeletionCron();
        this.appPasswordEnabled = settings.isAppPasswordEnabled();
        this.isFileListPageEnabled = settings.isFileListPageEnabled();
        this.isAdminDashboardButtonEnabled = settings.isAdminDashboardButtonEnabled();
        this.disableEncryption = settings.isEncryptionDisabled();
        this.disableUploadPassword = settings.isDisableUploadPassword();
        this.disablePreview = settings.isDisablePreview();
        this.metadataStrippingEnabled = settings.isMetadataStrippingEnabled();
        this.maxPreviewSizeBytes = settings.getMaxPreviewSizeBytes();
        this.defaultHomePage = settings.getDefaultHomePage();
        this.keepIndefinitelyAdminOnly = settings.isKeepIndefinitelyAdminOnly();
        this.hideFromListAdminOnly = settings.isHideFromListAdminOnly();
        this.discordWebhookEnabled = settings.isDiscordWebhookEnabled();
        this.discordWebhookUrl = settings.getDiscordWebhookUrl();
        this.emailNotificationsEnabled = settings.isEmailNotificationsEnabled();
        this.emailFrom = settings.getEmailFrom();
        this.emailTo = settings.getEmailTo();
        this.smtpHost = settings.getSmtpHost();
        this.smtpPort = settings.getSmtpPort();
        this.smtpUsername = settings.getSmtpUsername();
        this.smtpPassword = settings.getSmtpPassword();
        this.smtpUseTls = settings.isSmtpUseTls();
        this.notificationBatchEnabled = settings.isNotificationBatchEnabled();
        this.notificationBatchMinutes = settings.getNotificationBatchMinutes();
        this.simplifiedShareLinks = settings.isSimplifiedShareLinks();
        this.shareLinksDisabled = settings.isShareLinksDisabled();
        this.appName = settings.getAppName();
        this.logoFileName = settings.getLogoFileName();
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getLogoFileName() {
        return logoFileName;
    }

    public void setLogoFileName(String logoFileName) {
        this.logoFileName = logoFileName;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public long getMaxFileLifeTime() {
        return maxFileLifeTime;
    }

    public void setMaxFileLifeTime(long maxFileLifeTime) {
        this.maxFileLifeTime = maxFileLifeTime;
    }

    public String getFileStoragePath() {
        return fileStoragePath;
    }

    public void setFileStoragePath(String fileStoragePath) {
        this.fileStoragePath = fileStoragePath;
    }

    public String getLogStoragePath() {
        return logStoragePath;
    }

    public void setLogStoragePath(String logStoragePath) {
        this.logStoragePath = logStoragePath;
    }

    public String getFileDeletionCron() {
        return fileDeletionCron;
    }

    public void setFileDeletionCron(String fileDeletionCron) {
        this.fileDeletionCron = fileDeletionCron;
    }

    public boolean isAppPasswordEnabled() {
        return appPasswordEnabled;
    }

    public void setAppPasswordEnabled(boolean appPasswordEnabled) {
        this.appPasswordEnabled = appPasswordEnabled;
    }

    public String getAppPasswordHash() {
        return appPasswordHash;
    }

    public void setAppPasswordHash(String appPasswordHash) {
        this.appPasswordHash = appPasswordHash;
    }

    public String getAdminPasswordHash() {
        return adminPasswordHash;
    }

    public void setAdminPasswordHash(String adminPasswordHash) {
        this.adminPasswordHash = adminPasswordHash;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getSessionLifetime() {
        return sessionLifetime;
    }

    public void setSessionLifetime(long sessionLifetime) {
        this.sessionLifetime = sessionLifetime;
    }

    public boolean isFileListPageEnabled() {
        return isFileListPageEnabled;
    }

    public void setFileListPageEnabled(boolean fileListPageEnabled) {
        isFileListPageEnabled = fileListPageEnabled;
    }

    public boolean isAdminDashboardButtonEnabled() {
        return isAdminDashboardButtonEnabled;
    }

    public void setAdminDashboardButtonEnabled(boolean adminDashboardButtonEnabled) {
        isAdminDashboardButtonEnabled = adminDashboardButtonEnabled;
    }

    public boolean isDisableEncryption() {
        return disableEncryption;
    }

    public void setDisableEncryption(boolean disableEncryption) {
        this.disableEncryption = disableEncryption;
    }

    public boolean isDisableUploadPassword() {
        return disableUploadPassword;
    }

    public void setDisableUploadPassword(boolean disableUploadPassword) {
        this.disableUploadPassword = disableUploadPassword;
    }

    public boolean isDisablePreview() {
        return disablePreview;
    }

    public void setDisablePreview(boolean disablePreview) {
        this.disablePreview = disablePreview;
    }

    public boolean isMetadataStrippingEnabled() {
        return metadataStrippingEnabled;
    }

    public void setMetadataStrippingEnabled(boolean metadataStrippingEnabled) {
        this.metadataStrippingEnabled = metadataStrippingEnabled;
    }

    public long getMaxPreviewSizeBytes() {
        return maxPreviewSizeBytes;
    }

    public void setMaxPreviewSizeBytes(long maxPreviewSizeBytes) {
        this.maxPreviewSizeBytes = maxPreviewSizeBytes;
    }

    public String getDefaultHomePage() {
        return defaultHomePage;
    }

    public void setDefaultHomePage(String defaultHomePage) {
        this.defaultHomePage = defaultHomePage;
    }

    public boolean isKeepIndefinitelyAdminOnly() {
        return keepIndefinitelyAdminOnly;
    }

    public void setKeepIndefinitelyAdminOnly(boolean keepIndefinitelyAdminOnly) {
        this.keepIndefinitelyAdminOnly = keepIndefinitelyAdminOnly;
    }

    public boolean isHideFromListAdminOnly() {
        return hideFromListAdminOnly;
    }

    public void setHideFromListAdminOnly(boolean hideFromListAdminOnly) {
        this.hideFromListAdminOnly = hideFromListAdminOnly;
    }

    public boolean isDiscordWebhookEnabled() {
        return discordWebhookEnabled;
    }

    public void setDiscordWebhookEnabled(boolean discordWebhookEnabled) {
        this.discordWebhookEnabled = discordWebhookEnabled;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl;
    }

    public void setDiscordWebhookUrl(String discordWebhookUrl) {
        this.discordWebhookUrl = discordWebhookUrl;
    }

    public boolean isEmailNotificationsEnabled() {
        return emailNotificationsEnabled;
    }

    public void setEmailNotificationsEnabled(boolean emailNotificationsEnabled) {
        this.emailNotificationsEnabled = emailNotificationsEnabled;
    }

    public String getEmailFrom() {
        return emailFrom;
    }

    public void setEmailFrom(String emailFrom) {
        this.emailFrom = emailFrom;
    }

    public String getEmailTo() {
        return emailTo;
    }

    public void setEmailTo(String emailTo) {
        this.emailTo = emailTo;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = smtpHost;
    }

    public Integer getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(Integer smtpPort) {
        this.smtpPort = smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public void setSmtpUsername(String smtpUsername) {
        this.smtpUsername = smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public void setSmtpPassword(String smtpPassword) {
        this.smtpPassword = smtpPassword;
    }

    public boolean isSmtpUseTls() {
        return smtpUseTls;
    }

    public void setSmtpUseTls(boolean smtpUseTls) {
        this.smtpUseTls = smtpUseTls;
    }

    public boolean isNotificationBatchEnabled() {
        return notificationBatchEnabled;
    }

    public void setNotificationBatchEnabled(boolean notificationBatchEnabled) {
        this.notificationBatchEnabled = notificationBatchEnabled;
    }

    public Integer getNotificationBatchMinutes() {
        return notificationBatchMinutes;
    }

    public void setNotificationBatchMinutes(Integer notificationBatchMinutes) {
        this.notificationBatchMinutes = notificationBatchMinutes;
    }

    public boolean isSimplifiedShareLinks() {
        return simplifiedShareLinks;
    }

    public void setSimplifiedShareLinks(boolean simplifiedShareLinks) {
        this.simplifiedShareLinks = simplifiedShareLinks;
    }

    public boolean isShareLinksDisabled() {
        return shareLinksDisabled;
    }

    public void setShareLinksDisabled(boolean shareLinksDisabled) {
        this.shareLinksDisabled = shareLinksDisabled;
    }
}
