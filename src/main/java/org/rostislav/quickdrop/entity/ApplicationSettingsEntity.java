package org.rostislav.quickdrop.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;

/**
 * Persistent store for all application-wide configuration.
 *
 * <p>Only a single row (id = 1) is ever created. {@link org.rostislav.quickdrop.service.ApplicationSettingsService}
 * initialises defaults on startup and is the only intended writer. Reads are served from a
 * Spring cache ({@code applicationSettings}) to avoid a database hit on every request.
 */
@Entity
public class ApplicationSettingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Maximum allowed upload size in bytes.
     */
    private long maxFileSize;

    /** Number of days before a non-pinned file is eligible for automatic deletion. */
    private long maxFileLifeTime;

    /** File-system path (relative or absolute) where uploaded files are stored. */
    private String fileStoragePath;

    /** File-system path for log output. */
    private String logStoragePath;

    /** Spring cron expression that controls when the scheduled file-deletion job runs. */
    private String fileDeletionCron;

    /** Whether a site-wide application password is enforced for all users. */
    private boolean appPasswordEnabled;

    /** BCrypt hash of the application-level access password. */
    private String appPasswordHash;

    /** BCrypt hash of the administrator password. */
    private String adminPasswordHash;

    /** HTTP session timeout in minutes. */
    private long sessionLifetime;

    /** Whether the public /file/list page is accessible to unauthenticated users. */
    private boolean isFileListPageEnabled;

    /** Whether the Admin Dashboard navigation button is shown to non-admin users. */
    private boolean isAdminDashboardButtonEnabled;

    /** When {@code true}, AES encryption is not applied even if a password is provided. */
    private boolean disableEncryption;

    /** When {@code true}, upload-time passwords are rejected entirely. */
    private boolean disableUploadPassword;

    /** When {@code true}, in-browser file preview is disabled. */
    private boolean disablePreview;

    /** Whether EXIF and other metadata is stripped from images on upload. */
    private boolean metadataStrippingEnabled;

    /** Maximum file size (bytes) that is previewed without a manual override. */
    private long maxPreviewSizeBytes;

    /** Page shown when navigating to "/". One of: "upload", "paste", "list". */
    private String defaultHomePage;

    /** When {@code true}, only admins may pin files with "keep indefinitely". */
    private boolean keepIndefinitelyAdminOnly;

    /** When {@code true}, only admins may hide files from the public listing. */
    private boolean hideFromListAdminOnly;

    /** Whether Discord webhook notifications are enabled. */
    private boolean discordWebhookEnabled;

    /** Full HTTPS URL of the Discord webhook endpoint. */
    private String discordWebhookUrl;

    /** Whether email notifications are enabled. */
    private boolean emailNotificationsEnabled;

    /** RFC-5321 sender address used in notification e-mails. */
    private String emailFrom;

    /** Comma-separated list of notification recipients. */
    private String emailTo;

    /** SMTP server hostname. */
    private String smtpHost;

    /** SMTP server port (default 587). */
    private Integer smtpPort;

    /** SMTP authentication username. */
    private String smtpUsername;

    /** SMTP authentication password (stored in plaintext; use a dedicated app password). */
    private String smtpPassword;

    /** Whether STARTTLS is requested on the SMTP connection. */
    private boolean smtpUseTls;

    /** Whether implicit TLS (SMTPS) is used instead of STARTTLS. */
    private boolean smtpUseSsl;

    /** Whether notifications are queued and dispatched in periodic batches. */
    private boolean notificationBatchEnabled;

    /** Interval in minutes between batch notification flushes. */
    private Integer notificationBatchMinutes;

    /**
     * When {@code true}, share tokens are generated without expiry or download limits
     * and the existing unlimited token is reused.
     */
    private boolean simplifiedShareLinks;

    /** When {@code true}, share-link generation is disabled entirely. */
    private boolean shareLinksDisabled;

    /** Whether the Pastebin feature is available. */
    private boolean pastebinEnabled;

    /** Custom application name displayed in the UI (defaults to "QuickDrop"). */
    private String appName;

    /** Filename of the custom logo stored under the {@code branding/} directory, or {@code null} for the default. */
    private String logoFileName;

    /** BCP-47 language tag used as the default locale (e.g. "en", "de"). */
    private String defaultLanguage = "en";

    /**
     * Whether Discord/email notifications are sent for file upload events.
     */
    private boolean notifyOnUpload = true;
    /**
     * Whether notifications are sent for direct file download events.
     */
    private boolean notifyOnDownload = true;
    /**
     * Whether notifications are sent when a file's expiry is renewed.
     */
    private boolean notifyOnRenewal = true;
    /**
     * Whether notifications are sent when a file is deleted.
     */
    private boolean notifyOnDeletion = true;
    /**
     * Whether notifications are sent when a share token is created.
     */
    private boolean notifyOnShareCreate = true;
    /**
     * Whether notifications are sent when a file is downloaded via a share token. Defaults to {@code false} to avoid spam.
     */
    private boolean notifyOnShareDownload = false;
    /**
     * Whether notifications are sent when a new paste is created.
     */
    private boolean notifyOnPasteCreate = true;
    /**
     * Whether notifications are sent when a paste is viewed. Defaults to {@code false} to avoid spam.
     */
    private boolean notifyOnPasteView = false;
    /**
     * Whether notifications are sent when a paste is edited.
     */
    private boolean notifyOnPasteEdit = true;

    public ApplicationSettingsEntity() {
    }

    /**
     * Convenience constructor that copies values from a view-model.
     * Does not copy the app password — use {@link #setAppPasswordHash(String)} separately.
     *
     * @param settings the view-model populated from the settings form
     */
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
        this.smtpUseSsl = settings.isSmtpUseSsl();
        this.notificationBatchEnabled = settings.isNotificationBatchEnabled();
        this.notificationBatchMinutes = settings.getNotificationBatchMinutes();
        this.simplifiedShareLinks = settings.isSimplifiedShareLinks();
        this.shareLinksDisabled = settings.isShareLinksDisabled();
        this.pastebinEnabled = settings.isPastebinEnabled();
        this.appName = settings.getAppName();
        this.logoFileName = settings.getLogoFileName();
        this.defaultLanguage = settings.getDefaultLanguage() != null ? settings.getDefaultLanguage() : "en";
        this.notifyOnUpload = settings.isNotifyOnUpload();
        this.notifyOnDownload = settings.isNotifyOnDownload();
        this.notifyOnRenewal = settings.isNotifyOnRenewal();
        this.notifyOnDeletion = settings.isNotifyOnDeletion();
        this.notifyOnShareCreate = settings.isNotifyOnShareCreate();
        this.notifyOnShareDownload = settings.isNotifyOnShareDownload();
        this.notifyOnPasteCreate = settings.isNotifyOnPasteCreate();
        this.notifyOnPasteView = settings.isNotifyOnPasteView();
        this.notifyOnPasteEdit = settings.isNotifyOnPasteEdit();
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

    public boolean isShareLinksDisabled() {
        return shareLinksDisabled;
    }

    public void setShareLinksDisabled(boolean shareLinksDisabled) {
        this.shareLinksDisabled = shareLinksDisabled;
    }

    public boolean isPastebinEnabled() {
        return pastebinEnabled;
    }

    public void setPastebinEnabled(boolean pastebinEnabled) {
        this.pastebinEnabled = pastebinEnabled;
    }

    public boolean isNotifyOnUpload() {
        return notifyOnUpload;
    }

    public void setNotifyOnUpload(boolean notifyOnUpload) {
        this.notifyOnUpload = notifyOnUpload;
    }

    public boolean isNotifyOnDownload() {
        return notifyOnDownload;
    }

    public void setNotifyOnDownload(boolean notifyOnDownload) {
        this.notifyOnDownload = notifyOnDownload;
    }

    public boolean isNotifyOnRenewal() {
        return notifyOnRenewal;
    }

    public void setNotifyOnRenewal(boolean notifyOnRenewal) {
        this.notifyOnRenewal = notifyOnRenewal;
    }

    public boolean isNotifyOnDeletion() {
        return notifyOnDeletion;
    }

    public void setNotifyOnDeletion(boolean notifyOnDeletion) {
        this.notifyOnDeletion = notifyOnDeletion;
    }

    public boolean isNotifyOnShareCreate() {
        return notifyOnShareCreate;
    }

    public void setNotifyOnShareCreate(boolean notifyOnShareCreate) {
        this.notifyOnShareCreate = notifyOnShareCreate;
    }

    public boolean isNotifyOnShareDownload() {
        return notifyOnShareDownload;
    }

    public void setNotifyOnShareDownload(boolean notifyOnShareDownload) {
        this.notifyOnShareDownload = notifyOnShareDownload;
    }

    public boolean isNotifyOnPasteCreate() {
        return notifyOnPasteCreate;
    }

    public void setNotifyOnPasteCreate(boolean notifyOnPasteCreate) {
        this.notifyOnPasteCreate = notifyOnPasteCreate;
    }

    public boolean isNotifyOnPasteView() {
        return notifyOnPasteView;
    }

    public void setNotifyOnPasteView(boolean notifyOnPasteView) {
        this.notifyOnPasteView = notifyOnPasteView;
    }

    public boolean isNotifyOnPasteEdit() {
        return notifyOnPasteEdit;
    }

    public void setNotifyOnPasteEdit(boolean notifyOnPasteEdit) { this.notifyOnPasteEdit = notifyOnPasteEdit; }
}
