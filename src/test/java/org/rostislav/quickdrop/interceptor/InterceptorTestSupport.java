package org.rostislav.quickdrop.interceptor;

import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared fixtures for interceptor tests in this package. Mirrors
 * {@code org.rostislav.quickdrop.controller.ControllerTestSupport} (not reused directly since
 * that class is package-private in a different test package -- Track A/B split keeps each
 * package self-contained per the parallel test-writing plan).
 */
abstract class InterceptorTestSupport extends QuickdropIntegrationTest {

    protected static final String ADMIN_PASSWORD = "admin-test-pw-1234";

    @Autowired
    protected FileRepository fileRepository;
    @Autowired
    protected ApplicationSettingsService applicationSettingsService;
    @Autowired
    protected SessionService sessionService;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected ApplicationSettingsRepository applicationSettingsRepository;
    @Autowired
    protected CacheManager cacheManager;

    protected void ensureAdminPasswordSet() {
        if (!applicationSettingsService.isAdminPasswordSet()) {
            applicationSettingsService.setAdminPassword(ADMIN_PASSWORD);
        }
    }

    protected void updateSettings(Consumer<ApplicationSettingsEntity> mutator) {
        ApplicationSettingsEntity settings = applicationSettingsRepository.findById(1L).orElseThrow();
        mutator.accept(settings);
        applicationSettingsRepository.save(settings);
        var cache = cacheManager.getCache("applicationSettings");
        if (cache != null) {
            cache.clear();
        }
    }

    protected MockHttpSession adminSession() {
        ensureAdminPasswordSet();
        String token = sessionService.addAdminToken(UUID.randomUUID().toString());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("admin-session-token", token);
        return session;
    }

    protected MockHttpSession fileSession(String uuid, String password) {
        String token = sessionService.addFileSessionToken(UUID.randomUUID().toString(), password, uuid);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("file-session-token", token);
        return session;
    }

    protected StoredFile createFile(String name, byte[] content, String password) throws IOException {
        StoredFile file = new StoredFile();
        file.uuid = UUID.randomUUID().toString();
        file.name = name;
        file.size = content.length;
        file.uploadDate = LocalDate.now();
        if (password != null) {
            file.passwordHash = passwordEncoder.encode(password);
        }
        Files.write(storageDir.resolve(file.uuid), content);
        return fileRepository.save(file);
    }

    protected StoredFile createFile(String name, byte[] content) throws IOException {
        return createFile(name, content, null);
    }
}
