package org.rostislav.quickdrop.repository;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Repository for {@link FileHistoryLog} audit records.
 *
 * <p>Provides analytics queries (counts per event type, per file) and a bulk-delete
 * operation used when a file is purged from the system.
 */
public interface FileHistoryLogRepository extends JpaRepository<FileHistoryLog, Long> {

    /**
     * Returns the total number of events of a given type across all files.
     *
     * @param eventType the event category to count
     * @return total event count
     */
    long countByEventType(FileHistoryType eventType);

    /**
     * Returns the number of events of a given type recorded against a specific file.
     *
     * @param uuid      UUID of the file
     * @param eventType the event category to count
     * @return per-file event count
     */
    @Query("SELECT COUNT(fhl) FROM FileHistoryLog fhl WHERE fhl.file.uuid = :uuid AND fhl.eventType = :eventType")
    long countByFileAndType(String uuid, FileHistoryType eventType);

    /**
     * Returns all history entries for a given file UUID, most recent first.
     *
     * @param uuid UUID of the file
     * @return ordered list of log entries
     */
    @Query("SELECT fhl FROM FileHistoryLog fhl WHERE fhl.file.uuid = :uuid ORDER BY fhl.eventDate DESC")
    List<FileHistoryLog> findByFileUuidOrderByEventDateDesc(String uuid);

    /**
     * Deletes all log entries associated with the given file ID.
     * Must be called before deleting the parent {@link org.rostislav.quickdrop.entity.FileEntity}
     * to satisfy the foreign-key constraint.
     *
     * @param fileId database ID of the file whose history should be cleared
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM FileHistoryLog fhl WHERE fhl.file.id = :fileId")
    void deleteByFileId(Long fileId);
}
