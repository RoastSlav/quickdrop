package org.rostislav.quickdrop.service;

import jakarta.servlet.http.HttpServletRequest;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.entity.StoredFile;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.model.FileEntityView;
import org.rostislav.quickdrop.model.FileSession;
import org.rostislav.quickdrop.model.UploadRequest;
import org.rostislav.quickdrop.repository.FileRepository;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import org.rostislav.quickdrop.storage.StorageService;

import java.time.LocalDate;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.safeNumber;

/**
 * Read-only query service for files, pastes, and share tokens.
 *
 * <p>All methods are non-mutating — no database writes, no cache evictions. This
 * service populates the {@code publicFiles}, {@code adminFiles}, and
 * {@code adminDeletedFiles} caches via {@link org.springframework.cache.annotation.Cacheable}.
 *
 * <p>Authorization helpers ({@link #isAuthorizedForFile}, {@link #isAuthorizedToEdit})
 * centralise session-token validation so controllers and interceptors don't duplicate
 * the logic.
 */
@Service
public class FileQueryService {
    private final UploadRepository uploadRepository;
    private final FileRepository fileRepository;
    private final ShareTokenRepository shareTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;
    private final StorageService storageService;

    public FileQueryService(UploadRepository uploadRepository,
                            FileRepository fileRepository,
                            ShareTokenRepository shareTokenRepository,
                            PasswordEncoder passwordEncoder,
                            ApplicationSettingsService applicationSettingsService,
                            SessionService sessionService,
                            StorageService storageService) {
        this.uploadRepository = uploadRepository;
        this.fileRepository = fileRepository;
        this.shareTokenRepository = shareTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
        this.storageService = storageService;
    }

    /**
     * Returns the upload (file or paste) with the given UUID, or empty if not found.
     */
    public Optional<Upload> getFile(String uuid) {
        return uploadRepository.findByUUID(uuid);
    }

    /**
     * Returns {@code true} if the current request may view the file identified by {@code uuid}.
     *
     * <p>Edit-only files are always viewable — the restriction is on editing, not reading.
     * Password-protected files require a valid file-session token in the HTTP session.
     */
    public boolean isAuthorizedForFile(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) {
            return false;
        }
        if (upload.isEditOnly()) {
            return true;
        }
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return true;
        }
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        return sessionToken != null && sessionService.validateFileSessionToken(sessionToken.toString(), uuid);
    }

    /**
     * Returns {@code true} if the current request may edit the file identified by {@code uuid}.
     *
     * <p>Immutable files always return {@code false} regardless of session state. Admins may
     * always edit. For password-protected files a valid file-session token must be present in
     * the HTTP session. A file/paste with <em>no</em> password has no credential to check
     * against, so — matching the precedent set by {@link org.rostislav.quickdrop.controller.FileViewController}'s
     * delete-authorization check for the same "no password = no owner proof" case — only an
     * admin may edit it; this is deliberately stricter than {@link #isAuthorizedForFile}
     * (view), which is public for no-password uploads by design.
     */
    public boolean isAuthorizedToEdit(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) {
            return false;
        }
        if (upload.isImmutable()) {
            return false;
        }
        if (sessionService.hasValidAdminSession(request)) {
            return true;
        }
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return false;
        }
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        return sessionToken != null && sessionService.validateFileSessionToken(sessionToken.toString(), uuid);
    }

    public boolean checkFilePassword(String uuid, String password) {
        Optional<Upload> referenceByUUID = uploadRepository.findByUUID(uuid);
        if (referenceByUUID.isEmpty()) {
            return false;
        }
        Upload upload = referenceByUUID.get();
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(password, upload.passwordHash);
    }

    @Cacheable(value = "publicFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<StoredFile> getVisibleFiles(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findAllNotHiddenFiles(pageable);
        }
        return fileRepository.searchNotHiddenFiles(query, pageable);
    }

    public long calculateTotalSpaceUsed() {
        return safeNumber(fileRepository.totalFileSizeForFilesOnly());
    }

    public long getFileCount() {
        return fileRepository.countFiles();
    }

    @Cacheable(value = "adminFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntityView> getFilesWithDownloadCounts(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findFilesWithDownloadCounts(pageable);
        }
        return fileRepository.searchFilesWithDownloadCounts(query, pageable);
    }

    @Cacheable(value = "adminDeletedFiles", key = "'page:' + #pageable.pageNumber + ':size:' + #pageable.pageSize + ':q:' + (#query == null ? '' : #query.toLowerCase())")
    public Page<FileEntityView> getDeletedFilesWithDownloadCounts(Pageable pageable, String query) {
        if (query == null || query.isBlank()) {
            return fileRepository.findDeletedFilesWithDownloadCounts(pageable);
        }
        return fileRepository.searchDeletedFilesWithDownloadCounts(query, pageable);
    }

    public Optional<ShareTokenEntity> getShareTokenEntityByToken(String token) {
        return shareTokenRepository.findByShareToken(token);
    }

    /**
     * Returns a filtered, paginated view of share tokens.
     *
     * @param today     reference date for expiry checks; pass {@link LocalDate#now()}
     * @param isPaste   when non-null, limits to tokens for pastes ({@code true}) or files ({@code false})
     * @param noExpiry  when {@code true}, includes only tokens with no expiration date
     * @param unlimited when {@code true}, includes only tokens with an unlimited download allowance
     * @param query     optional name/UUID fragment; {@code null} returns all matching records
     */
    public Page<ShareTokenEntity> getFilteredShareTokens(LocalDate today, Boolean isPaste, boolean noExpiry,
                                                         boolean unlimited, String query, Pageable pageable) {
        return shareTokenRepository.findFiltered(today, isPaste, noExpiry, unlimited, query, pageable);
    }

    public boolean fileExistsInFileSystem(String uuid) {
        return storageService.exists(uuid);
    }

    /**
     * Returns {@code true} when the upload should be stored AES-encrypted on disk.
     *
     * <p>Edit-only pastes are never encrypted even when a password is provided — the server
     * must be able to read and serve their content without a per-request password.
     */
    public boolean shouldEncrypt(UploadRequest request) {
        return request.password != null && !request.password.isBlank()
                && applicationSettingsService.isEncryptionEnabled()
                && !request.editOnly;
    }

    /**
     * Retrieves the plaintext password bound to the caller's file-session token, or
     * {@code null} if no valid token is present in the HTTP session.
     *
     * <p>Package-private — used by {@link FileDownloadService} to decrypt AES-encrypted
     * files without exposing the session-token internals beyond this package.
     */
    String getFilePasswordFromSessionToken(HttpServletRequest request) {
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        if (sessionToken == null) {
            return null;
        }
        FileSession fileSession = sessionService.getPasswordForFileSessionToken(sessionToken.toString());
        return fileSession == null ? null : fileSession.getPassword();
    }
}
