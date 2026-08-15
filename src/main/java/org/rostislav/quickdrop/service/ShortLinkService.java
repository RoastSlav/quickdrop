package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.ShortLink;
import org.rostislav.quickdrop.entity.Upload;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.model.LinkVerdict;
import org.rostislav.quickdrop.model.ShortLinkResult;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.rostislav.quickdrop.util.FileUtils.validateShareToken;

/**
 * Owns short-link creation, revocation, and cleanup logic for every {@link ShortLink}
 * target type.
 *
 * <p>{@link FileLifecycleService} keeps its {@code generateShareToken}/{@code revokeShareToken}
 * public methods so no controller call site had to change when this feature was carved out —
 * they now delegate here. {@link RedirectLink} (plain URL shortening) creation and resolution
 * live directly on this service since there was no pre-existing caller to preserve.
 */
@Service
public class ShortLinkService {
    private static final Logger logger = LoggerFactory.getLogger(ShortLinkService.class);

    private final ShortLinkRepository shortLinkRepository;
    private final UploadRepository uploadRepository;
    private final SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final ShareEncryptionService shareEncryptionService;
    private final FileDownloadService fileDownloadService;
    private final ShortCodeService shortCodeService;
    private final LinkGuard linkGuard;
    private final ApplicationSettingsService applicationSettingsService;
    private final ActivityLogRepository activityLogRepository;

    public ShortLinkService(ShortLinkRepository shortLinkRepository,
                            UploadRepository uploadRepository,
                            SessionService sessionService,
                            PasswordEncoder passwordEncoder,
                            ShareEncryptionService shareEncryptionService,
                            FileDownloadService fileDownloadService,
                            ShortCodeService shortCodeService,
                            LinkGuard linkGuard,
                            ApplicationSettingsService applicationSettingsService,
                            ActivityLogRepository activityLogRepository) {
        this.shortLinkRepository = shortLinkRepository;
        this.uploadRepository = uploadRepository;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.shareEncryptionService = shareEncryptionService;
        this.fileDownloadService = fileDownloadService;
        this.shortCodeService = shortCodeService;
        this.linkGuard = linkGuard;
        this.applicationSettingsService = applicationSettingsService;
        this.activityLogRepository = activityLogRepository;
    }

    /**
     * Creates (or returns an existing) upload-share link for the file identified by
     * {@code uuid}.
     *
     * <p>When both {@code expirationDate} and {@code remainingUses} are {@code null}, an
     * existing unlimited link for the file is reused (with a refreshed {@code createdAt})
     * rather than minting a new one, to avoid link proliferation.
     *
     * @throws IllegalArgumentException if no file with the given UUID exists
     */
    public UploadShareLink createUploadLink(String uuid, LocalDate expirationDate, Integer remainingUses) {
        Upload upload = requireUpload(uuid);

        if (expirationDate == null && remainingUses == null) {
            Optional<UploadShareLink> existing = findUnlimitedShareToken(upload);
            if (existing.isPresent()) {
                UploadShareLink link = existing.get();
                link.createdAt = LocalDateTime.now();
                return shortLinkRepository.save(link);
            }
        }

        String code = shortCodeService.generateUniqueCode(applicationSettingsService.getShortenerCodeLength());
        UploadShareLink link = new UploadShareLink(code, upload, expirationDate, remainingUses);
        shortLinkRepository.save(link);
        return link;
    }

    /**
     * Creates an upload-share link for an AES-encrypted file, then kicks off background
     * re-encryption of a sidecar copy keyed with a fresh per-share key.
     *
     * <p>The plaintext password is read from the HTTP session on the request thread
     * <em>before</em> the background task starts — the session must not be accessed from
     * another thread. The link's {@code sidecarReady} flag is {@code false} until the
     * background task completes; the caller should surface this to the UI for large files.
     *
     * @param sessionToken the file-session token used to retrieve the encryption password
     * @return a {@link ShortLinkResult} containing the new link and the per-share key;
     *         the key must be embedded in the share URL so the recipient can decrypt the sidecar
     * @throws IllegalArgumentException if no file with the given UUID exists
     */
    public ShortLinkResult createEncryptedUploadLink(String uuid, LocalDate expirationDate, String sessionToken, Integer remainingUses) {
        Upload upload = requireUpload(uuid);

        if (!upload.encrypted) {
            UploadShareLink link = createUploadLink(uuid, expirationDate, remainingUses);
            return new ShortLinkResult(link, null);
        }

        String shareKey = java.util.UUID.randomUUID().toString();
        String code = shortCodeService.generateUniqueCode(applicationSettingsService.getShortenerCodeLength());

        // Pre-fetch the password on the request thread — HTTP session must not be
        // accessed from the background encryption thread.
        String plainPassword = sessionService.getPasswordForFileSessionToken(sessionToken).getPassword();

        UploadShareLink link = new UploadShareLink(code, upload, expirationDate, remainingUses);
        link.shareKeyHash = passwordEncoder.encode(shareKey);
        link.sidecarReady = false;
        shortLinkRepository.save(link);

        shareEncryptionService.encryptSidecarAsync(upload.uuid, code, shareKey, plainPassword,
                link.getId(), shortLinkRepository);

        logger.info("Share link saved; sidecar encryption submitted in background for upload: {}", upload.name);
        return new ShortLinkResult(link, shareKey);
    }

    /**
     * Revokes the upload-share link with the given id: deletes its sidecar file (if
     * present) and removes the link record.
     *
     * @return the upload the link pointed at, so the caller can write an audit log entry —
     *         empty if the link id doesn't exist or its upload reference is already gone
     */
    public Optional<Upload> revokeUploadLink(Long id) {
        return shortLinkRepository.findUploadLinkById(id).map(link -> {
            fileDownloadService.deleteShareSidecar(link);
            shortLinkRepository.delete(link);
            logger.info("Share link {} revoked by admin", link.code);
            return link.upload;
        });
    }

    /**
     * Deletes every share link (and any encrypted sidecar) pointing at {@code upload}.
     * Called when the upload itself is deleted.
     */
    public void purgeLinksForUpload(Upload upload) {
        shortLinkRepository.findAllByFile(upload).forEach(fileDownloadService::deleteShareSidecar);
        shortLinkRepository.deleteAllByFile(upload);
    }

    /**
     * Creates a redirect (plain URL-shortener) link.
     *
     * <p>The destination is normalized and safety-checked by {@link LinkGuard} before
     * anything is persisted; {@code targetUrl} on the saved entity is always the
     * absolute, explicit-scheme form it returns. When {@code customAlias} is blank, a
     * random code is generated instead.
     *
     * @throws LinkRejectedException if the destination is blocked, or the alias is
     *                               invalid/reserved/already taken
     */
    public RedirectLink createRedirectLink(String rawUrl, LocalDate expirationDate, Integer remainingUses,
                                           String customAlias, boolean createdByAdmin, String creatorIp) {
        LinkVerdict verdict = linkGuard.checkForCreation(rawUrl);
        if (!verdict.allowed()) {
            activityLogRepository.save(new ActivityLog(EventType.SHORTLINK_BLOCKED, creatorIp, null));
            throw new LinkRejectedException(verdict.reasonCode(), verdict.userMessage());
        }

        String code;
        if (customAlias != null && !customAlias.isBlank()) {
            if (!applicationSettingsService.isShortenerCustomAliasEnabled()) {
                throw new LinkRejectedException("invalid_alias", "Custom aliases are disabled.");
            }
            if (applicationSettingsService.isShortenerCustomAliasAdminOnly() && !createdByAdmin) {
                throw new LinkRejectedException("invalid_alias", "Custom aliases are restricted to administrators.");
            }
            ShortCodeService.AliasVerdict aliasVerdict = shortCodeService.validateAlias(customAlias);
            if (!aliasVerdict.ok()) {
                throw new LinkRejectedException("invalid_alias", aliasVerdict.message());
            }
            code = customAlias;
        } else {
            code = shortCodeService.generateUniqueCode(applicationSettingsService.getShortenerCodeLength());
        }

        RedirectLink link = new RedirectLink();
        link.code = code;
        link.targetUrl = verdict.normalizedUrl();
        link.expirationDate = expirationDate;
        link.remainingUses = remainingUses;
        link.createdAt = LocalDateTime.now();
        link.createdByAdmin = createdByAdmin;
        link.creatorIp = creatorIp;
        shortLinkRepository.save(link);
        activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_CREATE, creatorIp, null));
        logger.info("Redirect link {} created for {}", code, verdict.normalizedUrl());
        return link;
    }

    /**
     * Revokes the redirect link with the given id.
     *
     * @return {@code true} if a redirect link with that id existed and was deleted
     */
    public boolean revokeRedirectLink(Long id, String adminIp) {
        return shortLinkRepository.findRedirectLinkById(id)
                .map(link -> {
                    activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_REVOKE, adminIp, null));
                    shortLinkRepository.delete(link);
                    logger.info("Redirect link {} revoked by admin", link.code);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Writes a {@code SHORTLINK_BLOCKED} audit-log row without touching the link's use
     * counters. Used where a block is detected on a page-render path (deciding whether to
     * show the interstitial at all) rather than on the path that actually consumes a visit —
     * see {@link #resolveAndRecordVisit(String, String, String)} for that case.
     */
    public void logBlockedVisit(ShortLink link, String ipAddress, String userAgent) {
        activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_BLOCKED, ipAddress, userAgent));
    }

    /**
     * Resolves {@code code} to its link and records a visit (use-count increment, and
     * remaining-uses decrement when capped) on success.
     *
     * <p>For redirect links, destination safety is re-checked via
     * {@link LinkGuard#checkForRedirect} — a host that was safe when the link was created
     * can be repointed at a private address later (DNS rebinding), so this is not a
     * one-time gate. Visit recording here is not the atomic claim-before-streaming pattern
     * {@link org.rostislav.quickdrop.repository.ShortLinkRepository#decrementDownloadCount}
     * uses for file downloads — a redirect has no expensive resource to over-serve, so a
     * benign race under concurrent requests isn't worth the extra query.
     *
     * @return the link if {@code code} exists, is still valid, and (for redirect links)
     *         still passes the safety check; empty otherwise
     */
    public Optional<ShortLink> resolveAndRecordVisit(String code) {
        return resolveAndRecordVisit(code, null, null);
    }

    /**
     * Same as {@link #resolveAndRecordVisit(String)}, additionally writing an audit-log row
     * for the visit — gated by {@code shortenerClickLoggingEnabled} — and for a block
     * ({@code SHORTLINK_BLOCKED}, always logged regardless of that setting, since a run of
     * blocked visits from one address is the signal an admin needs to see).
     *
     * @param ipAddress requester IP for the audit log; may be {@code null}
     * @param userAgent requester User-Agent for the audit log; may be {@code null}
     */
    public Optional<ShortLink> resolveAndRecordVisit(String code, String ipAddress, String userAgent) {
        Optional<ShortLink> found = shortLinkRepository.findByCode(code);
        if (found.isEmpty() || !validateShareToken(found.get())) {
            return Optional.empty();
        }
        ShortLink link = found.get();
        if (link instanceof RedirectLink redirectLink) {
            if (!linkGuard.checkForRedirect(redirectLink.targetUrl).allowed()) {
                activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_BLOCKED, ipAddress, userAgent));
                return Optional.empty();
            }
        }
        link.useCount++;
        if (link.remainingUses != null) {
            link.remainingUses--;
        }
        shortLinkRepository.save(link);
        if (applicationSettingsService.isShortenerClickLoggingEnabled()) {
            activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_VISIT, ipAddress, userAgent));
        }
        return Optional.of(link);
    }

    private Upload requireUpload(String uuid) {
        return uploadRepository.findByUUID(uuid)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
    }

    private Optional<UploadShareLink> findUnlimitedShareToken(Upload upload) {
        return shortLinkRepository.findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(upload);
    }
}
