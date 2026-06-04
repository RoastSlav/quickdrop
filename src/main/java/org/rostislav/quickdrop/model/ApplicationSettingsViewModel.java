package org.rostislav.quickdrop.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.storage.StorageBackend;

/**
 * Form-backing model for the admin settings page.
 *
 * <p>Mirrors {@link org.rostislav.quickdrop.entity.ApplicationSettingsEntity} with
 * Jakarta Bean Validation annotations for server-side constraint checking. The
 * {@link #ApplicationSettingsViewModel(org.rostislav.quickdrop.entity.ApplicationSettingsEntity)}
 * constructor maps from the entity, and
 * {@link org.rostislav.quickdrop.service.ApplicationSettingsService#updateApplicationSettings}
 * maps back to the entity on save.
 *
 * <p>Note: {@link #maxFileSize} is converted between bytes (entity) and megabytes
 * (form field) in the controller layer.
 */
public class ApplicationSettingsViewModel {
    private Long id;

    @Min(value = 1, message = "{validation.number.positive}")
    private long maxFileSize;
    @Min(value = 1, message = "{validation.number.positive}")
    private long maxFileLifeTime;
    @NotBlank(message = "{validation.required}")
    private String fileStoragePath;
    private String logStoragePath;
    @NotBlank(message = "{validation.required}")
    private String fileDeletionCron;
    private boolean appPasswordEnabled;
    private String appPassword;
    @Min(value = 0, message = "{validation.number.nonNegative}")
    private long sessionLifeTime;
    private boolean isFileListPageEnabled;
    private boolean isAdminDashboardButtonEnabled;
    private boolean encryptionEnabled;
    private boolean uploadPasswordEnabled;
    private boolean previewEnabled;
    private boolean metadataStrippingEnabled;
    @Min(value = 1, message = "{validation.number.positive}")
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
    private boolean smtpUseSsl;
    private boolean notificationBatchEnabled;
    private Integer notificationBatchMinutes;
    private boolean simplifiedShareLinks;
    private boolean shareLinksEnabled;
    private boolean uploadEnabled;
    private boolean uploadAdminOnly;
    private boolean pastebinEnabled;
    private String appName;
    private String logoFileName;
    private String defaultLanguage;
    private boolean notifyOnUpload;
    private boolean notifyOnDownload;
    private boolean notifyOnRenewal;
    private boolean notifyOnDeletion;
    private boolean notifyOnShareCreate;
    private boolean notifyOnShareDownload;
    private boolean notifyOnPasteCreate;
    private boolean notifyOnPasteView;
    private boolean notifyOnPasteEdit;
    private StorageBackend storageBackend;
    private String s3Endpoint;
    private String s3Bucket;
    private String s3Region;
    private String s3AccessKey;
    private String s3SecretKey;
    private boolean s3PathStyle;
    private String s3KeyPrefix;

    public ApplicationSettingsViewModel() {
    }

    public ApplicationSettingsViewModel(ApplicationSettingsEntity settings) {
        this.id = settings.getId();
        this.maxFileSize = settings.getMaxFileSize();
        this.maxFileLifeTime = settings.getMaxFileLifeTime();
        this.fileStoragePath = settings.getFileStoragePath();
        this.logStoragePath = settings.getLogStoragePath();
        this.fileDeletionCron = settings.getFileDeletionCron();
        this.appPasswordEnabled = settings.isAppPasswordEnabled();
        this.sessionLifeTime = settings.getSessionLifetime();
        this.isFileListPageEnabled = settings.isFileListPageEnabled();
        this.isAdminDashboardButtonEnabled = settings.isAdminDashboardButtonEnabled();
        this.encryptionEnabled = settings.isEncryptionEnabled();
        this.uploadPasswordEnabled = settings.isUploadPasswordEnabled();
        this.previewEnabled = settings.isPreviewEnabled();
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
        this.smtpUseSsl = settings.isSmtpUseSsl();
        this.notificationBatchEnabled = settings.isNotificationBatchEnabled();
        this.notificationBatchMinutes = settings.getNotificationBatchMinutes();
        this.simplifiedShareLinks = settings.isSimplifiedShareLinks();
        this.shareLinksEnabled = settings.isShareLinksEnabled();
        this.uploadEnabled = settings.isUploadEnabled();
        this.uploadAdminOnly = settings.isUploadAdminOnly();
        this.pastebinEnabled = settings.isPastebinEnabled();
        this.appName = settings.getAppName();
        this.logoFileName = settings.getLogoFileName();
        this.defaultLanguage = settings.getDefaultLanguage();
        this.notifyOnUpload = settings.isNotifyOnUpload();
        this.notifyOnDownload = settings.isNotifyOnDownload();
        this.notifyOnRenewal = settings.isNotifyOnRenewal();
        this.notifyOnDeletion = settings.isNotifyOnDeletion();
        this.notifyOnShareCreate = settings.isNotifyOnShareCreate();
        this.notifyOnShareDownload = settings.isNotifyOnShareDownload();
        this.notifyOnPasteCreate = settings.isNotifyOnPasteCreate();
        this.notifyOnPasteView = settings.isNotifyOnPasteView();
        this.notifyOnPasteEdit = settings.isNotifyOnPasteEdit();
        this.storageBackend = settings.getStorageBackend();
        this.s3Endpoint = settings.getS3Endpoint();
        this.s3Bucket = settings.getS3Bucket();
        this.s3Region = settings.getS3Region();
        this.s3AccessKey = settings.getS3AccessKey();
        this.s3SecretKey = settings.getS3SecretKey();
        this.s3PathStyle = settings.isS3PathStyle();
        this.s3KeyPrefix = settings.getS3KeyPrefix();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getAppPassword() {
        return appPassword;
    }

    public void setAppPassword(String appPassword) {
        this.appPassword = appPassword;
    }

    public long getSessionLifeTime() {
        return sessionLifeTime;
    }

    public void setSessionLifeTime(long sessionLifeTime) {
        this.sessionLifeTime = sessionLifeTime;
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

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public void setEncryptionEnabled(boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }

    public boolean isUploadPasswordEnabled() {
        return uploadPasswordEnabled;
    }

    public void setUploadPasswordEnabled(boolean uploadPasswordEnabled) {
        this.uploadPasswordEnabled = uploadPasswordEnabled;
    }

    public boolean isPreviewEnabled() {
        return previewEnabled;
    }

    public void setPreviewEnabled(boolean previewEnabled) {
        this.previewEnabled = previewEnabled;
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

    public boolean isSmtpUseSsl() {
        return smtpUseSsl;
    }

    public void setSmtpUseSsl(boolean smtpUseSsl) {
        this.smtpUseSsl = smtpUseSsl;
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

    public boolean isShareLinksEnabled() {
        return shareLinksEnabled;
    }

    public void setShareLinksEnabled(boolean shareLinksEnabled) {
        this.shareLinksEnabled = shareLinksEnabled;
    }

    public boolean isUploadEnabled() {
        return uploadEnabled;
    }

    public void setUploadEnabled(boolean uploadEnabled) {
        this.uploadEnabled = uploadEnabled;
    }

    public boolean isUploadAdminOnly() {
        return uploadAdminOnly;
    }

    public void setUploadAdminOnly(boolean uploadAdminOnly) {
        this.uploadAdminOnly = uploadAdminOnly;
    }

    public boolean isPastebinEnabled() {
        return pastebinEnabled;
    }

    public void setPastebinEnabled(boolean pastebinEnabled) {
        this.pastebinEnabled = pastebinEnabled;
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

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public boolean isNotifyOnUpload() {
        return notifyOnUpload;
    }

    public void setNotifyOnUpload(boolean v) {
        this.notifyOnUpload = v;
    }

    public boolean isNotifyOnDownload() {
        return notifyOnDownload;
    }

    public void setNotifyOnDownload(boolean v) {
        this.notifyOnDownload = v;
    }

    public boolean isNotifyOnRenewal() {
        return notifyOnRenewal;
    }

    public void setNotifyOnRenewal(boolean v) {
        this.notifyOnRenewal = v;
    }

    public boolean isNotifyOnDeletion() {
        return notifyOnDeletion;
    }

    public void setNotifyOnDeletion(boolean v) {
        this.notifyOnDeletion = v;
    }

    public boolean isNotifyOnShareCreate() {
        return notifyOnShareCreate;
    }

    public void setNotifyOnShareCreate(boolean v) {
        this.notifyOnShareCreate = v;
    }

    public boolean isNotifyOnShareDownload() {
        return notifyOnShareDownload;
    }

    public void setNotifyOnShareDownload(boolean v) {
        this.notifyOnShareDownload = v;
    }

    public boolean isNotifyOnPasteCreate() {
        return notifyOnPasteCreate;
    }

    public void setNotifyOnPasteCreate(boolean v) {
        this.notifyOnPasteCreate = v;
    }

    public boolean isNotifyOnPasteView() {
        return notifyOnPasteView;
    }

    public void setNotifyOnPasteView(boolean v) {
        this.notifyOnPasteView = v;
    }

    public boolean isNotifyOnPasteEdit() {
        return notifyOnPasteEdit;
    }

    public void setNotifyOnPasteEdit(boolean v) {
        this.notifyOnPasteEdit = v;
    }

    public StorageBackend getStorageBackend() { return storageBackend; }
    public void setStorageBackend(StorageBackend storageBackend) { this.storageBackend = storageBackend; }
    public String getS3Endpoint() { return s3Endpoint; }
    public void setS3Endpoint(String s3Endpoint) { this.s3Endpoint = s3Endpoint; }
    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }
    public String getS3Region() { return s3Region; }
    public void setS3Region(String s3Region) { this.s3Region = s3Region; }
    public String getS3AccessKey() { return s3AccessKey; }
    public void setS3AccessKey(String s3AccessKey) { this.s3AccessKey = s3AccessKey; }
    public String getS3SecretKey() { return s3SecretKey; }
    public void setS3SecretKey(String s3SecretKey) { this.s3SecretKey = s3SecretKey; }
    public boolean isS3PathStyle() { return s3PathStyle; }
    public void setS3PathStyle(boolean s3PathStyle) { this.s3PathStyle = s3PathStyle; }
    public String getS3KeyPrefix() { return s3KeyPrefix; }
    public void setS3KeyPrefix(String s3KeyPrefix) { this.s3KeyPrefix = s3KeyPrefix; }
}
