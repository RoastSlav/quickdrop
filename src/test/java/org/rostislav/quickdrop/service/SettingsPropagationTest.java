package org.rostislav.quickdrop.service;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.Test;
import org.rostislav.quickdrop.model.ApplicationSettingsViewModel;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression guards for "does a settings-page change actually take effect without a restart."
 * These pin the intended contract regardless of *how* it's implemented underneath, so a future
 * refactor of the settings-propagation path can't silently reintroduce a restart requirement.
 *
 * <p>{@code sessionCreated_appliesCurrentSessionLifetimeInSeconds} in particular guards a fixed
 * bug: {@code WebConfig}'s {@code ServletContextInitializer} only ever applied the session
 * lifetime once at startup, so a settings change had no effect until the app restarted.
 * {@link SessionService#sessionCreated} now reads the setting live for every new session.
 */
class SettingsPropagationTest extends QuickdropIntegrationTest {

    @Autowired
    private ApplicationSettingsService applicationSettingsService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private MultipartConfigElement multipartConfigElement;

    @Autowired
    private ScheduleService scheduleService;

    @Test
    void sessionCreated_appliesCurrentSessionLifetimeInSeconds() {
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setSessionLifeTime(42);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        try {
            MockHttpSession session = new MockHttpSession();
            sessionService.sessionCreated(new HttpSessionEvent(session));

            // setSessionTimeout(int) on the servlet context (WebConfig, applied once at
            // startup) takes minutes; HttpSession#setMaxInactiveInterval(int) takes seconds --
            // the fix must convert, not pass the raw value through.
            assertEquals(42 * 60, session.getMaxInactiveInterval(),
                    "a session created after the settings change must carry the newly configured lifetime");
        } finally {
            ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            cleanup.setSessionLifeTime(30);
            applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
        }
    }

    @Test
    void multipartConfigElement_reflectsCurrentMaxFileSizeWithoutRestart() {
        long original = applicationSettingsService.getMaxFileSize();
        long newMax = original + 12_345L;
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setMaxFileSize(newMax);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        try {
            assertEquals(newMax, multipartConfigElement.getMaxFileSize(),
                    "multipart max file size must reflect the just-saved setting without an app restart");
            assertEquals(newMax + 1024L * 1024L * 10L, multipartConfigElement.getMaxRequestSize(),
                    "max request size must track max file size + the 10MB form-field overhead allowance");
        } finally {
            ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            cleanup.setMaxFileSize(original);
            applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
        }
    }

    @Test
    void updateApplicationSettings_reschedulesCleanupWithNewCronAndLifetime() {
        String newCron = "0 15 4 * * *";
        ApplicationSettingsViewModel vm = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
        vm.setFileDeletionCron(newCron);
        vm.setMaxFileLifeTime(45);
        applicationSettingsService.updateApplicationSettings(vm, null, null, false);

        try {
            assertEquals(newCron, ReflectionTestUtils.getField(scheduleService, "currentCron"));
            assertEquals(45L, ReflectionTestUtils.getField(scheduleService, "currentMaxFileLifeTime"));
        } finally {
            ApplicationSettingsViewModel cleanup = new ApplicationSettingsViewModel(applicationSettingsService.getApplicationSettings());
            cleanup.setFileDeletionCron("0 0 2 * * *");
            cleanup.setMaxFileLifeTime(30);
            applicationSettingsService.updateApplicationSettings(cleanup, null, null, false);
        }
    }
}
