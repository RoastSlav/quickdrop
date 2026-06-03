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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.rostislav.quickdrop.util.DataValidator.safeNumber;

@Service
public class FileQueryService {
    private final UploadRepository uploadRepository;
    private final FileRepository fileRepository;
    private final ShareTokenRepository shareTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationSettingsService applicationSettingsService;
    private final SessionService sessionService;

    public FileQueryService(UploadRepository uploadRepository,
                            FileRepository fileRepository,
                            ShareTokenRepository shareTokenRepository,
                            PasswordEncoder passwordEncoder,
                            ApplicationSettingsService applicationSettingsService,
                            SessionService sessionService) {
        this.uploadRepository = uploadRepository;
        this.fileRepository = fileRepository;
        this.shareTokenRepository = shareTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationSettingsService = applicationSettingsService;
        this.sessionService = sessionService;
    }

    public Optional<Upload> getFile(String uuid) {
        return uploadRepository.findByUUID(uuid);
    }

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

    public boolean isAuthorizedToEdit(String uuid, HttpServletRequest request) {
        Upload upload = uploadRepository.findByUUID(uuid).orElse(null);
        if (upload == null) {
            return false;
        }
        if (upload.isImmutable()) {
            return false;
        }
        if (upload.passwordHash == null || upload.passwordHash.isBlank()) {
            return true;
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

    public Page<ShareTokenEntity> getFilteredShareTokens(LocalDate today, Boolean isPaste, boolean noExpiry,
                                                         boolean unlimited, String query, Pageable pageable) {
        return shareTokenRepository.findFiltered(today, isPaste, noExpiry, unlimited, query, pageable);
    }

    public boolean fileExistsInFileSystem(String uuid) {
        return Files.exists(Path.of(applicationSettingsService.getFileStoragePath(), uuid));
    }

    public boolean shouldEncrypt(UploadRequest request) {
        return request.password != null && !request.password.isBlank()
                && applicationSettingsService.isEncryptionEnabled()
                && !request.editOnly;
    }

    String getFilePasswordFromSessionToken(HttpServletRequest request) {
        Object sessionToken = request.getSession().getAttribute("file-session-token");
        if (sessionToken == null) {
            return null;
        }
        FileSession fileSession = sessionService.getPasswordForFileSessionToken(sessionToken.toString());
        return fileSession == null ? null : fileSession.getPassword();
    }
}
