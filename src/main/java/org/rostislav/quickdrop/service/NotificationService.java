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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    private final ApplicationSettingsService applicationSettingsService;
    private final RestTemplate restTemplate = new RestTemplate();
    private volatile JavaMailSenderImpl cachedMailSender;
    private volatile String mailSenderKey;

    public NotificationService(ApplicationSettingsService applicationSettingsService) {
        this.applicationSettingsService = applicationSettingsService;
    }

    public void notifyFileAction(FileEntity fileEntity, FileHistoryType type, String ipAddress, String userAgent) {
        if (fileEntity == null) {
            return;
        }

        String discordUrl = safeString(applicationSettingsService.getDiscordWebhookUrl());
        boolean shouldSendDiscord = applicationSettingsService.isDiscordWebhookEnabled() && !discordUrl.isBlank();
        boolean shouldSendEmail = applicationSettingsService.isEmailNotificationsEnabled() && hasEmailRecipients();

        if (!shouldSendDiscord && !shouldSendEmail) {
            return;
        }

        String event = switch (type) {
            case UPLOAD -> "uploaded";
            case DOWNLOAD -> "downloaded";
            case RENEWAL -> "renewed";
        };

        String summary = "File '" + fileEntity.name + "' (" + fileEntity.uuid + ") was " + event + ".";
        StringBuilder detailsBuilder = new StringBuilder();
        if (type == FileHistoryType.UPLOAD) {
            detailsBuilder.append("Size: ").append(fileEntity.size).append(" bytes");
        }
        String details = detailsBuilder.toString();
        String formattedMessage = details.isBlank() ? summary : summary + "\n---\n" + details;

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
}
