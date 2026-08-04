package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.EventType;
import org.springframework.context.MessageSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Plain Mockito unit tests for {@link NotificationService}. No Spring context or live
 * SMTP/Discord endpoint is used — network-touching success paths (an actual webhook
 * POST or SMTP send) are intentionally NOT exercised here since this sandbox has no
 * guaranteed network access; see the skip note on {@link #sendTestDiscordDoesNotAttemptNetworkCallForInvalidUrl()}
 * and {@link #sendTestEmailFailsFastWhenHostMissing()} for what IS covered instead
 * (fast-fail validation paths that never reach the network).
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private ApplicationSettingsService settings;
    @Mock
    private MessageSource messageSource;

    private NotificationService newService() {
        // Mirror MessageSource's real fallback behaviour: return the supplied default
        // message text rather than null, since no real i18n bundle is loaded here.
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        return new NotificationService(settings, messageSource);
    }

    private Upload someFile() {
        StoredFile f = new StoredFile();
        f.uuid = "uuid-1";
        f.name = "report.pdf";
        f.size = 1024;
        return f;
    }

    @Test
    void notifyFileActionIsNoOpForNullFile() {
        NotificationService service = newService();
        assertDoesNotThrow(() -> service.notifyFileAction(null, EventType.UPLOAD));
        verifyNoInteractions(settings);
    }

    @Test
    void notifyFileActionDoesNothingWhenEventToggleDisabled() {
        when(settings.isNotifyOnUpload()).thenReturn(false);
        NotificationService service = newService();

        service.notifyFileAction(someFile(), EventType.UPLOAD);

        // Only the toggle itself should have been consulted -- no channel config lookups.
        verify(settings).isNotifyOnUpload();
        verify(settings, never()).getDiscordWebhookUrl();
        verify(settings, never()).getEmailTo();
    }

    @Test
    void notifyFileActionDoesNothingWhenNoChannelsConfigured() {
        when(settings.isNotifyOnUpload()).thenReturn(true);
        when(settings.isDiscordWebhookEnabled()).thenReturn(false);
        when(settings.getDiscordWebhookUrl()).thenReturn("");
        when(settings.isEmailNotificationsEnabled()).thenReturn(false);
        NotificationService service = newService();

        assertDoesNotThrow(() -> service.notifyFileAction(someFile(), EventType.UPLOAD));
    }

    @Test
    void notifySystemEventDoesNothingWhenToggleDisabled() {
        when(settings.isNotifyOnStorageDown()).thenReturn(false);
        NotificationService service = newService();

        service.notifySystemEvent(EventType.STORAGE_BACKEND_DOWN);

        verify(settings).isNotifyOnStorageDown();
        verify(settings, never()).getDiscordWebhookUrl();
    }

    @Test
    void sendTestDiscordFailsFastWhenUrlBlank() {
        when(settings.getDiscordWebhookUrl()).thenReturn("");
        NotificationService service = newService();

        NotificationService.NotificationTestResult result = service.sendTestDiscord();

        assertFalse(result.success());
        assertNotNull(result.message());
    }

    @Test
    void sendTestDiscordDoesNotAttemptNetworkCallForInvalidUrl() {
        // A non-Discord host must be rejected before any network I/O is attempted --
        // this is the SSRF guard added alongside the webhook feature. We can't observe
        // "no network call happened" directly without a network sandbox, but a fast,
        // synchronous failure return (rather than a multi-second connect-timeout hang)
        // is strong evidence the REST call was never made.
        when(settings.getDiscordWebhookUrl()).thenReturn("https://evil.example.com/webhook");
        NotificationService service = newService();

        long start = System.nanoTime();
        NotificationService.NotificationTestResult result = service.sendTestDiscord();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.success());
        assertTrue(elapsedMs < 2000, "invalid webhook URL should fail fast without attempting a network connection, took " + elapsedMs + "ms");
    }

    @Test
    void sendTestEmailFailsFastWhenHostMissing() {
        when(settings.getEmailFrom()).thenReturn("noreply@example.com");
        when(settings.getEmailTo()).thenReturn("admin@example.com");
        when(settings.getSmtpHost()).thenReturn("");
        NotificationService service = newService();

        NotificationService.NotificationTestResult result = service.sendTestEmail();

        assertFalse(result.success());
        assertNotNull(result.message());
    }

    @Test
    void sendTestEmailFailsFastWhenNoRecipients() {
        when(settings.getEmailFrom()).thenReturn("noreply@example.com");
        when(settings.getEmailTo()).thenReturn("   ");
        when(settings.getSmtpHost()).thenReturn("smtp.example.com");
        NotificationService service = newService();

        NotificationService.NotificationTestResult result = service.sendTestEmail();

        assertFalse(result.success());
    }
}
