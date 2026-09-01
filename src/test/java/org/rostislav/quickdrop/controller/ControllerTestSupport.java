package org.rostislav.quickdrop.controller;

import org.junit.jupiter.api.function.Executable;
import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.rostislav.quickdrop.entity.Paste;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.PasteRepository;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.rostislav.quickdrop.service.AnalyticsService;
import org.rostislav.quickdrop.service.AsyncFileMergeService;
import org.rostislav.quickdrop.service.SessionService;
import org.rostislav.quickdrop.support.QuickdropIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared fixtures for controller slice tests under this package.
 *
 * <p>Bypasses the real chunked-upload pipeline (see {@code AsyncFileMergeService}) by
 * writing content directly to the redirected storage dir ({@code QuickdropIntegrationTest#storageDir})
 * and inserting rows via the repositories, so controller tests stay fast and focused on
 * controller-layer behaviour (routing, auth, model attributes) rather than re-exercising
 * the upload/merge path, which is Track A's territory.
 */
abstract class ControllerTestSupport extends QuickdropIntegrationTest {

    protected static final String ADMIN_PASSWORD = "admin-test-pw-1234";

    @Autowired
    protected FileRepository fileRepository;
    @Autowired
    protected PasteRepository pasteRepository;
    @Autowired
    protected ShortLinkRepository shareTokenRepository;
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
    @Autowired
    protected AsyncFileMergeService asyncFileMergeService;
    @Autowired
    protected AnalyticsService analyticsService;

    /**
     * Polls {@code AsyncFileMergeService#getUploadStatus} until the async chunk-merge task
     * for {@code uploadId} leaves the "processing" state, or fails the test after 10s.
     * {@code FileRestController} always submits chunks with {@code waitForCompletion=false},
     * so completion must be observed this way rather than from the HTTP response itself.
     */
    protected AsyncFileMergeService.UploadProgress waitForUploadCompletion(String uploadId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        AsyncFileMergeService.UploadProgress progress;
        do {
            progress = asyncFileMergeService.getUploadStatus(uploadId);
            if (!"processing".equals(progress.status())) {
                return progress;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Upload " + uploadId + " did not leave 'processing' state in time (last: " + progress + ")");
    }

    /** Ensures an admin password is set, without creating a session (for tests that assert unauthenticated behaviour). */
    protected void ensureAdminPasswordSet() {
        if (!applicationSettingsService.isAdminPasswordSet()) {
            applicationSettingsService.setAdminPassword(ADMIN_PASSWORD);
        }
    }

    /** Directly mutates the settings row and evicts the settings cache, bypassing the admin form/API. */
    protected void updateSettings(Consumer<ApplicationSettingsEntity> mutator) {
        ApplicationSettingsEntity settings = applicationSettingsRepository.findById(1L).orElseThrow();
        mutator.accept(settings);
        applicationSettingsRepository.save(settings);
        var cache = cacheManager.getCache("applicationSettings");
        if (cache != null) {
            cache.clear();
        }
    }

    /** Ensures an admin password is set and returns a session pre-authenticated as admin. */
    protected MockHttpSession adminSession() {
        if (!applicationSettingsService.isAdminPasswordSet()) {
            applicationSettingsService.setAdminPassword(ADMIN_PASSWORD);
        }
        String token = sessionService.addAdminToken(UUID.randomUUID().toString());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("admin-session-token", token);
        return session;
    }

    /** A session carrying no admin/file credentials at all. */
    protected MockHttpSession anonymousSession() {
        return new MockHttpSession();
    }

    /** Registers a file-session token for {@code uuid} bound to {@code password} and returns a session carrying it. */
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

    protected StoredFile createHiddenFile(String name, byte[] content) throws IOException {
        StoredFile file = createFile(name, content, null);
        file.hidden = true;
        return fileRepository.save(file);
    }

    protected StoredFile createDeletedFile(String name, byte[] content) throws IOException {
        StoredFile file = createFile(name, content, null);
        file.deleted = true;
        return fileRepository.save(file);
    }

    protected StoredFile createFolderFile(String name, byte[] content, String folderName, String manifestJson) throws IOException {
        StoredFile file = createFile(name, content, null);
        file.folderUpload = true;
        file.folderName = folderName;
        file.folderManifest = manifestJson;
        return fileRepository.save(file);
    }

    protected Paste createPaste(String title, String content, String password, boolean immutable, boolean editOnly) throws IOException {
        Paste paste = new Paste();
        paste.uuid = UUID.randomUUID().toString();
        paste.name = title;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        paste.size = bytes.length;
        paste.hidden = true;
        paste.uploadDate = LocalDate.now();
        paste.immutable = immutable;
        paste.editOnly = editOnly;
        if (password != null) {
            paste.passwordHash = passwordEncoder.encode(password);
        }
        Files.write(storageDir.resolve(paste.uuid), bytes);
        return pasteRepository.save(paste);
    }

    protected Paste createDeletedPaste(String title, String content) throws IOException {
        Paste paste = createPaste(title, content, null, false, false);
        paste.deleted = true;
        return pasteRepository.save(paste);
    }

    protected UploadShareLink createShareToken(Upload upload, LocalDate expiry, Integer downloads) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UploadShareLink entity = new UploadShareLink(token, upload, expiry, downloads);
        return shareTokenRepository.save(entity);
    }

    /**
     * Retries {@code test} up to 3 times when it throws {@link ConcurrentModificationException}.
     *
     * <p>Testing an async {@code StreamingResponseBody} endpoint with {@code asyncDispatch()}
     * races the executor thread writing the body against Spring Security's
     * {@code HeaderWriterFilter} finishing the response on the main thread -- both mutate
     * {@code MockHttpServletResponse}'s header map, which isn't thread-safe. This is a
     * documented MockMvc-only limitation (spring-projects/spring-framework#31543, closed as
     * "not planned" since a real {@code HttpServletResponse} is unaffected), and the
     * maintainers' own recommended workaround is to retry.
     *
     * <p>Only safe to wrap around an idempotent request -- e.g. not a limited-use share-token
     * download, since the counter decrement happens before the point where this race can occur
     * and would already be spent on a retry.
     */
    protected static void retryOnMockMvcAsyncHeaderRace(Executable test) throws Throwable {
        for (int attempt = 1; ; attempt++) {
            try {
                test.execute();
                return;
            } catch (ConcurrentModificationException e) {
                if (attempt >= 3) throw e;
            }
        }
    }
}
