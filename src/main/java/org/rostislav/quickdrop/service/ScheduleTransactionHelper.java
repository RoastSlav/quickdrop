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

    /**
     * Removes all database records for the given UUIDs within a single transaction.
     *
     * @param uuids UUIDs of uploads whose records should be removed from the database
     */
    @Transactional
    public void softDeleteByUuids(List<String> uuids) {
        for (String uuid : uuids) {
            fileLifecycleService.removeFileFromDatabase(uuid);
        }
    }

    /**
     * Removes all expired/exhausted share token rows from the database within a
     * single transaction.
     *
     * <p>DB rows are deleted first so that, in the event of a crash after this
     * call returns, the tokens are already gone and any orphaned sidecars are
     * cleaned up by the daily orphan scan rather than leaving dangling token
     * records pointing at missing sidecars.
     *
     * @param tokens share token entities to delete
     */
    @Transactional
    public void deleteExpiredShareTokens(List<UploadShareLink> tokens) {
        shortLinkRepository.deleteAll(tokens);
    }

    /**
     * Removes all expired/exhausted redirect links within a single transaction, writing a
     * {@code SHORTLINK_EXPIRE} audit-log row for each first (system-triggered: no IP/user-agent).
     *
     * <p>Unlike {@link #deleteExpiredShareTokens}, this logs per link — redirect links have
     * no associated {@code Upload} to carry history on, so the activity log is the only
     * place an admin can see that a given link expired.
     *
     * @param links redirect link entities to log and delete
     */
    @Transactional
    public void deleteExpiredRedirectLinks(List<RedirectLink> links) {
        links.forEach(link -> activityLogRepository.save(new ActivityLog(link, EventType.SHORTLINK_EXPIRE, null, null)));
        shortLinkRepository.deleteAll(links);
    }

    /**
     * Soft-deletes uploads with the given IDs within a single transaction.
     *
     * @param ids file IDs to soft-delete via {@link FileLifecycleService}
     */
    @Transactional
    public void deleteFilesAndHistory(List<Long> ids) {
        ids.forEach(id -> uploadRepository.findById(id)
                .ifPresent(upload -> fileLifecycleService.removeFileFromDatabase(upload.uuid)));
    }
}
