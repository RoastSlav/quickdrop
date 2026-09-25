package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.ActivityLog;
import org.rostislav.quickdrop.entity.RedirectLink;
import org.rostislav.quickdrop.entity.UploadShareLink;
import org.rostislav.quickdrop.model.EventType;
import org.rostislav.quickdrop.repository.ActivityLogRepository;
import org.rostislav.quickdrop.repository.ShortLinkRepository;
import org.rostislav.quickdrop.repository.UploadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional helper for scheduled batch operations.
 *
 * <p>{@link ScheduleService} scheduled methods are invoked directly by the Spring
 * scheduler and therefore bypass the AOP proxy — any {@code @Transactional} annotation
 * placed on them is silently ignored. By delegating DB-heavy work to this helper bean,
 * Spring's proxy wraps each public method in a real transaction.
 */
@Service
public class ScheduleTransactionHelper {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleTransactionHelper.class);

    private final FileLifecycleService fileLifecycleService;
    private final ShortLinkRepository shortLinkRepository;
    private final UploadRepository uploadRepository;
    private final ActivityLogRepository activityLogRepository;

    public ScheduleTransactionHelper(FileLifecycleService fileLifecycleService,
                                     ShortLinkRepository shortLinkRepository,
                                     UploadRepository uploadRepository,
                                     ActivityLogRepository activityLogRepository) {
        this.fileLifecycleService = fileLifecycleService;
        this.shortLinkRepository = shortLinkRepository;
        this.uploadRepository = uploadRepository;
        this.activityLogRepository = activityLogRepository;
    }

    @Transactional
    public void softDeleteByUuids(List<String> uuids) {
        for (String uuid : uuids) {
            fileLifecycleService.removeFileFromDatabase(uuid);
        }
    }

    /**
     * DB rows are deleted first so a crash right after this call leaves orphaned sidecars
     * (cleaned up by the daily orphan scan) rather than dangling token records.
     */
    @Transactional
    public void deleteExpiredShareTokens(List<UploadShareLink> tokens) {
        shortLinkRepository.deleteAll(tokens);
    }

    /**
     * Unlike {@link #deleteExpiredShareTokens}, logs a {@code SHORTLINK_EXPIRE} row per link
     * first — a redirect link has no associated {@code Upload} to carry history on, so the
     * activity log is the only place an admin can see it expired.
     */
    @Transactional
    public void deleteExpiredRedirectLinks(List<RedirectLink> links) {
        links.forEach(link -> activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_EXPIRE, null, null)));
        shortLinkRepository.deleteAll(links);
    }

    @Transactional
    public void deleteFilesAndHistory(List<Long> ids) {
        ids.forEach(id -> uploadRepository.findById(id)
                .ifPresent(upload -> fileLifecycleService.removeFileFromDatabase(upload.uuid)));
    }
}
