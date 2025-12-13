package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    private static final long DEFAULT_FLUSH_POLL_SECONDS = 60;
    private final ApplicationSettingsService applicationSettingsService;
    private final RestTemplate restTemplate = new RestTemplate();
    private volatile JavaMailSenderImpl cachedMailSender;
    private volatile String mailSenderKey;
    private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();
    private volatile long lastFlushEpochMillis = System.currentTimeMillis();
    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;

    public NotificationService(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
        startSchedulerIfNeeded(shouldSendDiscord(), shouldSendEmail());
    }

    public void notifyFileAction(FileEntity fileEntity, FileHistoryType type, String ipAddress, String userAgent) {
        if (fileEntity == null) {
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
        };

        String summary = "File '" + fileEntity.name + "' (" + fileEntity.uuid + ") was " + event + ".";
        StringBuilder detailsBuilder = new StringBuilder();
        if (type == FileHistoryType.UPLOAD) {
            detailsBuilder.append("Size: ").append(fileEntity.size).append(" bytes");
        }
        String details = detailsBuilder.toString();
        String formattedMessage = details.isBlank() ? summary : summary + "\n---\n" + details;

        if (shouldUseBatching(shouldSendDiscord, shouldSendEmail)) {
            pendingMessages.add(formattedMessage);
            startSchedulerIfNeeded(shouldSendDiscord, shouldSendEmail);
            return;
        }

        if (shouldSendDiscord) {
            sendDiscord(formattedMessage);
        }

        if (shouldSendEmail) {
            sendEmail(event, summary, details);
        }
    }

    private boolean hasEmailRecipients() {
        String emailTo = applicationSettingsService.getEmailTo();
        if (emailTo == null) {
            return false;
        }
        return Arrays.stream(emailTo.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findAny()
                .isPresent();
    }

    private void sendDiscord(String content) {
        try {
            restTemplate.postForEntity(safeString(applicationSettingsService.getDiscordWebhookUrl()), Map.of("content", content), Void.class);
        } catch (Exception e) {
            logger.warn("Discord notification failed: {}", e.getMessage());
        }
    }

    public NotificationTestResult sendTestDiscord() {
        String url = safeString(applicationSettingsService.getDiscordWebhookUrl());
        if (url.isBlank()) {
            return NotificationTestResult.failure("Discord webhook URL is not configured.");
        }

        try {
            restTemplate.postForEntity(url, Map.of("content", "QuickDrop notification test (Discord)"), Void.class);
            return NotificationTestResult.success("Discord test notification sent.");
        } catch (Exception e) {
            logger.warn("Discord test notification failed: {}", e.getMessage());
            return NotificationTestResult.failure("Discord test failed: " + e.getMessage());
        }
    }

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

    public NotificationTestResult sendTestEmail() {
        try {
            JavaMailSenderImpl mailSender = resolveMailSender();
            String from = safeString(applicationSettingsService.getEmailFrom());
            String[] recipients = parseRecipients();
            if (mailSender == null || from.isBlank() || recipients.length == 0) {
                return NotificationTestResult.failure("Email settings incomplete (host/from/recipients).");
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(from);
            helper.setTo(recipients);
            helper.setSubject("QuickDrop test email");
            helper.setText("This is a QuickDrop notification test email. If you see this, SMTP settings are working.");

            mailSender.send(message);
            return NotificationTestResult.success("Email test notification sent.");
        } catch (Exception e) {
            logger.warn("Email test notification failed: {}", e.getMessage());
            return NotificationTestResult.failure("Email test failed: " + e.getMessage());
        }
    }

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

    private void flushPending(boolean sendDiscord, boolean sendEmail) {
        List<String> drained = pendingMessages.stream().toList();
        lastFlushEpochMillis = System.currentTimeMillis();
        pendingMessages.clear();

        if (drained.isEmpty()) {
            return;
        }

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

    public record NotificationTestResult(boolean success, String message) {
        public static NotificationTestResult success(String message) {
            return new NotificationTestResult(true, message);
        }

        public static NotificationTestResult failure(String message) {
            return new NotificationTestResult(false, message);
        }
    }

    private JavaMailSenderImpl resolveMailSender() {
        String host = safeString(applicationSettingsService.getSmtpHost());
        Integer port = applicationSettingsService.getSmtpPort();
        String username = safeString(applicationSettingsService.getSmtpUsername());
        String password = safeString(applicationSettingsService.getSmtpPassword());
        boolean useTls = applicationSettingsService.isSmtpUseTls();

        if (host.isBlank()) {
            return null;
        }

        String key = host + ":" + Objects.requireNonNullElse(port, 587) + "|" + username + "|" + password + "|" + useTls;

        if (key.equals(mailSenderKey) && cachedMailSender != null) {
            return cachedMailSender;
        }

        synchronized (this) {
            if (key.equals(mailSenderKey) && cachedMailSender != null) {
                return cachedMailSender;
            }

            JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
            mailSender.setHost(host);
            mailSender.setPort(Objects.requireNonNullElse(port, 587));
            mailSender.setUsername(username);
            mailSender.setPassword(password);

            var props = mailSender.getJavaMailProperties();
            boolean hasAuth = !username.isBlank();
            props.put("mail.smtp.auth", String.valueOf(hasAuth));
            props.put("mail.smtp.starttls.enable", String.valueOf(useTls));

            cachedMailSender = mailSender;
            mailSenderKey = key;
            return mailSender;
        }
    }

    private String[] parseRecipients() {
        return Optional.ofNullable(applicationSettingsService.getEmailTo())
                .map(value -> Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new))
                .orElseGet(() -> new String[0]);
    }

    private String safeValue(String value) {
        return value == null ? "unknown" : value;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private boolean shouldSendDiscord() {
        String discordUrl = safeString(applicationSettingsService.getDiscordWebhookUrl());
        return applicationSettingsService.isDiscordWebhookEnabled() && !discordUrl.isBlank();
    }

    private boolean shouldSendEmail() {
        return applicationSettingsService.isEmailNotificationsEnabled() && hasEmailRecipients();
    }

    private boolean shouldUseBatching(boolean sendDiscord, boolean sendEmail) {
        return applicationSettingsService.isNotificationBatchEnabled() && (sendDiscord || sendEmail);
    }

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
            scheduler.scheduleAtFixedRate(this::flushBatchIfDue, DEFAULT_FLUSH_POLL_SECONDS, DEFAULT_FLUSH_POLL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void stopSchedulerIfIdle() {
        synchronized (schedulerLock) {
            if (scheduler == null) {
                return;
            }

            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
