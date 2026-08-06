package org.rostislav.quickdrop.service;

import jakarta.mail.internet.MimeMessage;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.rostislav.quickdrop.util.DataValidator.safeString;

/**
 * Sends Discord webhook and SMTP email notifications for file events.
 *
 * <p>Two delivery modes are supported, selected at runtime from application settings:
 * <ul>
 *   <li><strong>Immediate</strong> — each event is dispatched on a dedicated
 *       single-thread executor ({@code notification-dispatch}).</li>
 *   <li><strong>Batched</strong> — events are queued and flushed together once
 *       per configured interval on the {@code notification-batch-flush} executor.</li>
 * </ul>
 */
@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    private static final long DEFAULT_FLUSH_POLL_SECONDS = 60;

    private final ApplicationSettingsService applicationSettingsService;
    private final MessageSource messageSource;
    /**
     * Built from the Spring-managed {@link RestTemplateBuilder} (rather than constructed
     * directly) so it picks up the app-wide {@code HttpClientSettings}, including the
     * {@link org.rostislav.quickdrop.config.HttpClientConfig#outboundHttpClientAddressFilter()}
     * SSRF filter -- a hand-built {@code RestTemplate} would bypass that auto-configuration.
     */
    private final RestTemplate restTemplate;
    private final java.util.concurrent.BlockingDeque<String> pendingMessages =
            new java.util.concurrent.LinkedBlockingDeque<>(10_000);
    private final Object schedulerLock = new Object();
    // Virtual-thread-per-task, not a single thread: notifications are independent and
    // order-insensitive, so a burst can dispatch concurrently instead of queuing behind
    // whichever one is blocked on a slow network call.
    private final ExecutorService notificationExecutor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notification-dispatch-", 0).factory());
    private volatile long lastFlushEpochMillis = System.currentTimeMillis();
    private ScheduledExecutorService scheduler;

    public NotificationService(ApplicationSettingsService applicationSettingsService, MessageSource messageSource,
                               RestTemplateBuilder restTemplateBuilder) {
        this.applicationSettingsService = applicationSettingsService;
        this.messageSource = messageSource;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Returns {@code true} if the per-event notification toggle for {@code type} is enabled.
     * System-only events ({@code SHARE_EXPIRE}, {@code SHARE_REVOKE}) are always silent.
     *
     * @param type the event type to check
     * @return {@code true} if notifications for this event type are enabled
     */
    private boolean isNotificationEventEnabled(EventType type) {
        return switch (type) {
            case UPLOAD -> applicationSettingsService.isNotifyOnUpload();
            case DOWNLOAD -> applicationSettingsService.isNotifyOnDownload();
            case RENEWAL -> applicationSettingsService.isNotifyOnRenewal();
            case DELETION -> applicationSettingsService.isNotifyOnDeletion();
            case SHARE_CREATE -> applicationSettingsService.isNotifyOnShareCreate();
            case SHARE_DOWNLOAD -> applicationSettingsService.isNotifyOnShareDownload();
            case PASTE_CREATE -> applicationSettingsService.isNotifyOnPasteCreate();
            case PASTE_VIEW -> applicationSettingsService.isNotifyOnPasteView();
            case PASTE_EDIT -> applicationSettingsService.isNotifyOnPasteEdit();
            case STORAGE_BACKEND_DOWN -> applicationSettingsService.isNotifyOnStorageDown();
            case STORAGE_BACKEND_UP -> applicationSettingsService.isNotifyOnStorageUp();
            // SHARE_EXPIRE, SHARE_REVOKE, all ADMIN_*, STARTUP, SHUTDOWN are always silent
            default -> false;
        };
    }

    /**
     * Sends (or enqueues, if batching is enabled) a notification for a file event.
     *
     * <p>Does nothing if the per-event toggle is off, both Discord and email notifications
     * are disabled, or no channels are configured.
     *
     * @param file the file that triggered the event; ignored if {@code null}
     * @param type the event type (upload, download, deletion, renewal, paste/share events)
     */
    public void notifyFileAction(Upload file, EventType type) {
        if (file == null) {
            return;
        }

        if (!isNotificationEventEnabled(type)) {
            return;
        }

        boolean shouldSendDiscord = shouldSendDiscord();
        boolean shouldSendEmail = shouldSendEmail();

        if (!shouldSendDiscord && !shouldSendEmail) {
            return;
        }

        String event = switch (type) {
            case UPLOAD -> "uploaded";
            case DOWNLOAD -> "downloaded";
            case RENEWAL -> "renewed";
            case DELETION -> "deleted";
            case PASTE_CREATE -> "created (paste)";
            case PASTE_VIEW -> "viewed (paste)";
            case PASTE_EDIT -> "edited (paste)";
            case SHARE_CREATE -> "share link created";
            case SHARE_DOWNLOAD -> "downloaded via share link";
            case SHARE_EXPIRE -> "share link expired";
            case SHARE_REVOKE -> "share link revoked";
            // ADMIN_* and SYSTEM events are filtered out by isNotificationEventEnabled;
            // this arm is unreachable in practice.
            default -> "";
        };

        String summary = "File '" + escapeDiscord(file.name) + "' (" + file.uuid + ") was " + event + ".";
        StringBuilder detailsBuilder = new StringBuilder();
        if (type == EventType.UPLOAD) {
            detailsBuilder.append("Size: ").append(file.size).append(" bytes");
        }
        String details = detailsBuilder.toString();
        String formattedMessage = details.isBlank() ? summary : summary + "\n---\n" + details;

        if (shouldUseBatching(shouldSendDiscord, shouldSendEmail)) {
            if (!pendingMessages.offer(formattedMessage)) {
                logger.warn("Notification queue full — discarding notification.");
            }
            startSchedulerIfNeeded(shouldSendDiscord, shouldSendEmail);
            return;
        }

        // Keep file actions responsive even when external notification endpoints are slow.
        notificationExecutor.submit(() -> {
            if (shouldSendDiscord) {
                sendDiscord(formattedMessage);
            }

            if (shouldSendEmail) {
                sendEmail(event, summary, details);
            }
        });
    }

    /**
     * Sends a system-level notification (no file context) for storage health events.
     * Respects the per-event toggles and configured channels.
     */
    public void notifySystemEvent(EventType type) {
        if (!isNotificationEventEnabled(type)) return;
        boolean sendDiscord = shouldSendDiscord();
        boolean sendEmail = shouldSendEmail();
        if (!sendDiscord && !sendEmail) return;

        String message = switch (type) {
            case STORAGE_BACKEND_DOWN ->
                    "Storage backend is unreachable. Uploads and downloads are disabled until the connection is restored.";
            case STORAGE_BACKEND_UP -> "Storage backend is reachable again. Service has been restored.";
            default -> type.name();
        };

        notificationExecutor.submit(() -> {
            if (sendDiscord) sendDiscord(message);
            if (sendEmail) sendEmail(type.name().toLowerCase(), message, "");
        });
    }

    /**
     * Returns {@code true} if the email-to list contains at least one non-blank address.
     */
    private boolean hasEmailRecipients() {
        String emailTo = safeString(applicationSettingsService.getEmailTo());
        if (emailTo.isBlank()) {
            return false;
        }
        return Arrays.stream(emailTo.split(","))
                .map(String::trim)
                .anyMatch(s -> !s.isEmpty());
    }

    /**
     * Returns {@code true} only when {@code url} is an {@code https://} URL pointing at a
     * Discord-owned domain ({@code discord.com} or {@code discordapp.com} and their subdomains).
     * Rejects {@code null}, blank, non-HTTPS, and any other hostname to prevent SSRF.
     *
     * @param url the webhook URL to validate
     * @return {@code true} if the URL is safe to call
     */
    private boolean isValidDiscordWebhookUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(scheme)) return false;
            if (host == null) return false;
            // Only allow Discord domains
            return host.equals("discord.com") || host.endsWith(".discord.com")
                    || host.equals("discordapp.com") || host.endsWith(".discordapp.com");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Escapes Discord Markdown special characters and neutralises {@code @everyone} / {@code @here}
     * pings by inserting a zero-width space after the {@code @} sign.
     *
     * @param text the raw text to escape; {@code null} returns an empty string
     * @return the escaped string safe for inclusion in a Discord message
     */
    private String escapeDiscord(String text) {
        if (text == null) return "";
        // Prevent @everyone and @here mentions
        text = text.replace("@everyone", "@​everyone").replace("@here", "@​here");
        // Escape Discord markdown special characters
        return text.replaceAll("([*_`~|>\\\\\\[\\]])", "\\\\$1");
    }

    /**
     * Posts {@code content} to the configured Discord webhook URL.
     * Failures are logged as warnings and do not propagate.
     *
     * @param content the message text to send
     */
    private void sendDiscord(String content) {
        String webhookUrl = safeString(applicationSettingsService.getDiscordWebhookUrl());
        if (!isValidDiscordWebhookUrl(webhookUrl)) {
            logger.warn("Discord notification blocked: URL failed validation — must be https://discord.com or https://discordapp.com");
            return;
        }
        try {
            restTemplate.postForEntity(webhookUrl, Map.of("content", content), Void.class);
        } catch (Exception e) {
            logger.warn("Discord notification failed: {}", e.getMessage());
        }
    }

    /**
     * Sends a test message to the configured Discord webhook and returns the result.
     *
     * @return a {@link NotificationTestResult} indicating success or failure with a localised message
     */
    public NotificationTestResult sendTestDiscord() {
        String url = safeString(applicationSettingsService.getDiscordWebhookUrl());
        if (url.isBlank()) {
            return NotificationTestResult.failure(messageSource.getMessage("page.settings.notifications.discord.missingUrl", null, "Discord webhook URL is not configured.", LocaleContextHolder.getLocale()));
        }

        if (!isValidDiscordWebhookUrl(url)) {
            logger.warn("Discord test notification blocked: URL failed validation — must be https://discord.com or https://discordapp.com");
            return NotificationTestResult.failure(messageSource.getMessage("page.settings.notifications.discord.invalidUrl", null, "Discord webhook URL is invalid. Only https://discord.com and https://discordapp.com URLs are allowed.", LocaleContextHolder.getLocale()));
        }

        try {
            restTemplate.postForEntity(url, Map.of("content", "QuickDrop notification test (Discord)"), Void.class);
            return NotificationTestResult.success(messageSource.getMessage("page.settings.notifications.discord.success", null, "Discord test notification sent.", LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            logger.warn("Discord test notification failed: {}", e.getMessage());
            String errorMsg = messageSource.getMessage("page.settings.notifications.discord.failed", new Object[]{summarizeReason(e.getMessage())}, "Discord test failed: " + summarizeReason(e.getMessage()), LocaleContextHolder.getLocale());
            return NotificationTestResult.failure(errorMsg);
        }
    }

    /**
     * Sends an email notification via the configured SMTP settings.
     * Failures are logged as warnings and do not propagate.
     *
     * @param event   human-readable event label (e.g. "uploaded")
     * @param summary one-line event summary for the email body
     * @param details additional details appended after the summary (may be empty)
     */
    private void sendEmail(String event, String summary, String details) {
        try {
            JavaMailSenderImpl mailSender = resolveMailSender();
            String from = safeString(applicationSettingsService.getEmailFrom());
            String[] recipients = parseRecipients();
            if (mailSender == null || from.isBlank() || recipients.length == 0) {
                return;
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(from);
            helper.setTo(recipients);
            helper.setSubject("QuickDrop file " + event);
            helper.setText(details.isBlank() ? summary : summary + "\n\n" + details);

            mailSender.send(message);
        } catch (Exception e) {
            logger.warn("Email notification failed: {}", e.getMessage());
        }
    }

    /**
     * Sends a test email via the current SMTP settings and returns the result.
     *
     * @return a {@link NotificationTestResult} indicating success or failure with a localised message
     */
    public NotificationTestResult sendTestEmail() {
        try {
            JavaMailSenderImpl mailSender = resolveMailSender();
            String from = safeString(applicationSettingsService.getEmailFrom());
            String[] recipients = parseRecipients();
            if (mailSender == null || from.isBlank() || recipients.length == 0) {
                return NotificationTestResult.failure(messageSource.getMessage("page.settings.notifications.email.incomplete", null, "Email settings incomplete (host/from/recipients).", LocaleContextHolder.getLocale()));
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(from);
            helper.setTo(recipients);
            helper.setSubject("QuickDrop test email");
            helper.setText("This is a QuickDrop notification test email. If you see this, SMTP settings are working.");

            mailSender.send(message);
            return NotificationTestResult.success(messageSource.getMessage("page.settings.notifications.email.success", null, "Email test notification sent.", LocaleContextHolder.getLocale()));
        } catch (Exception e) {
            logger.warn("Email test notification failed: {}", e.getMessage());
            String errorMsg = messageSource.getMessage("page.settings.notifications.email.failed", new Object[]{summarizeReason(e.getMessage())}, "Email test failed: " + summarizeReason(e.getMessage()), LocaleContextHolder.getLocale());
            return NotificationTestResult.failure(errorMsg);
        }
    }

    /**
     * Truncates and sanitises an exception message for display in user-facing error strings.
     *
     * @param message the raw exception message
     * @return a single-line string no longer than 160 characters
     */
    private String summarizeReason(String message) {
        if (message == null || message.isBlank()) {
            return "unexpected error";
        }
        String clean = message.replaceAll("[\r\n]+", " ").strip();
        if (clean.length() > 160) {
            clean = clean.substring(0, 160) + "…";
        }
        return clean;
    }

    /**
     * Called by the batch scheduler to decide whether queued messages should be flushed.
     */
    private void flushBatchIfDue() {
        try {
            boolean sendDiscord = shouldSendDiscord();
            boolean sendEmail = shouldSendEmail();

            if (!shouldUseBatching(sendDiscord, sendEmail)) {
                stopSchedulerIfIdle();
                flushPending(sendDiscord, sendEmail);
                return;
            }

            Integer intervalMinutes = applicationSettingsService.getNotificationBatchMinutes();
            if (intervalMinutes == null || intervalMinutes < 1) {
                flushPending(sendDiscord, sendEmail);
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastFlushEpochMillis >= TimeUnit.MINUTES.toMillis(intervalMinutes)) {
                flushPending(sendDiscord, sendEmail);
            }
        } catch (Exception e) {
            logger.warn("Batch flush failed: {}", e.getMessage());
        }
    }

    /**
     * Atomically drains all queued messages into a list.
     *
     * @return drained messages in arrival order
     */
    private List<String> drainPendingMessages() {
        List<String> drained = new ArrayList<>();
        String msg;
        while ((msg = pendingMessages.poll()) != null) {
            drained.add(msg);
        }
        return drained;
    }

    /**
     * Flushes all pending messages as a single batched notification.
     * Updates {@link #lastFlushEpochMillis} so the interval resets after each flush.
     *
     * @param sendDiscord whether to send to Discord
     * @param sendEmail   whether to send via email
     */
    private void flushPending(boolean sendDiscord, boolean sendEmail) {
        List<String> drained = drainPendingMessages();
        if (drained.isEmpty()) {
            return;
        }

        lastFlushEpochMillis = System.currentTimeMillis();

        String intervalLabel = Optional.ofNullable(applicationSettingsService.getNotificationBatchMinutes())
                .map(Object::toString)
                .orElse("?");
        String header = "Batched notifications (last " + intervalLabel + " minutes):";
        String content = header + "\n\n" + String.join("\n\n", drained);

        if (sendDiscord) {
            sendDiscord(content);
        }

        if (sendEmail) {
            sendEmail("batch", content, "");
        }
    }

    /**
     * Constructs a {@link JavaMailSenderImpl} from the current application settings.
     * Returns {@code null} if no SMTP host is configured.
     *
     * @return a configured mail sender, or {@code null} if the host is blank
     */
    private JavaMailSenderImpl resolveMailSender() {
        String host = safeString(applicationSettingsService.getSmtpHost());
        Integer port = applicationSettingsService.getSmtpPort();
        String username = safeString(applicationSettingsService.getSmtpUsername());
        String password = safeString(applicationSettingsService.getSmtpPassword());
        boolean useTls = applicationSettingsService.isSmtpUseTls();
        boolean useSsl = applicationSettingsService.isSmtpUseSsl();

        if (host.isBlank()) {
            return null;
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(Objects.requireNonNullElse(port, 587));
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        var props = mailSender.getJavaMailProperties();
        boolean hasAuth = !username.isBlank();
        props.put("mail.smtp.auth", String.valueOf(hasAuth));
        props.put("mail.smtp.starttls.enable", String.valueOf(!useSsl && useTls));
        props.put("mail.smtp.ssl.enable", String.valueOf(useSsl));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        if (useSsl) {
            props.put("mail.smtp.socketFactory.port", String.valueOf(Objects.requireNonNullElse(port, 465)));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        }

        return mailSender;
    }

    /**
     * Parses the comma-separated email recipient list from settings into an array.
     *
     * @return non-blank recipient addresses, or an empty array if none are configured
     */
    private String[] parseRecipients() {
        return Optional.ofNullable(applicationSettingsService.getEmailTo())
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new))
                .orElseGet(() -> new String[0]);
    }

    /**
     * Returns {@code true} if Discord notifications are enabled and a webhook URL is set.
     */
    private boolean shouldSendDiscord() {
        String discordUrl = safeString(applicationSettingsService.getDiscordWebhookUrl());
        return applicationSettingsService.isDiscordWebhookEnabled() && !discordUrl.isBlank();
    }

    /**
     * Returns {@code true} if email notifications are enabled and recipients are configured.
     */
    private boolean shouldSendEmail() {
        return applicationSettingsService.isEmailNotificationsEnabled() && hasEmailRecipients();
    }

    /**
     * Returns {@code true} if batch mode is enabled and at least one channel is active.
     */
    private boolean shouldUseBatching(boolean sendDiscord, boolean sendEmail) {
        return applicationSettingsService.isNotificationBatchEnabled() && (sendDiscord || sendEmail);
    }

    /**
     * Starts the batch-flush scheduler if it is not already running.
     * The scheduler polls every {@value #DEFAULT_FLUSH_POLL_SECONDS} seconds.
     *
     * @param sendDiscord whether Discord is currently active (used to decide if scheduler is needed)
     * @param sendEmail   whether email is currently active
     */
    private void startSchedulerIfNeeded(boolean sendDiscord, boolean sendEmail) {
        if (!shouldUseBatching(sendDiscord, sendEmail)) {
            stopSchedulerIfIdle();
            return;
        }

        synchronized (schedulerLock) {
            if (scheduler != null && !scheduler.isShutdown()) {
                return;
            }

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("notification-batch-flush");
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::flushBatchIfDue, DEFAULT_FLUSH_POLL_SECONDS, DEFAULT_FLUSH_POLL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Shuts down the batch-flush scheduler immediately if one is running.
     * Safe to call when batching is disabled or no notifications are pending.
     */
    private void stopSchedulerIfIdle() {
        synchronized (schedulerLock) {
            if (scheduler == null) {
                return;
            }

            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Result of a notification test (Discord or email).
     *
     * @param success {@code true} if the test message was delivered
     * @param message localised description of the outcome
     */
    public record NotificationTestResult(boolean success, String message) {
        public static NotificationTestResult success(String message) {
            return new NotificationTestResult(true, message);
        }

        public static NotificationTestResult failure(String message) {
            return new NotificationTestResult(false, message);
        }
    }
}
