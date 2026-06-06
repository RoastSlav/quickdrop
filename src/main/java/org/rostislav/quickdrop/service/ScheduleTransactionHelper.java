package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.rostislav.quickdrop.repository.ShareTokenRepository;
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
    private final ShareTokenRepository shareTokenRepository;
    private final UploadRepository uploadRepository;

    public ScheduleTransactionHelper(FileLifecycleService fileLifecycleService,
                                     ShareTokenRepository shareTokenRepository,
                                     UploadRepository uploadRepository) {
        this.fileLifecycleService = fileLifecycleService;
        this.shareTokenRepository = shareTokenRepository;
        this.uploadRepository = uploadRepository;
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
    public void deleteExpiredShareTokens(List<ShareTokenEntity> tokens) {
        shareTokenRepository.deleteAll(tokens);
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
