package org.rostislav.quickdrop.repository;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.entity.FileHistoryLog;
import org.rostislav.quickdrop.model.FileHistoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface FileHistoryLogRepository extends JpaRepository<FileHistoryLog, Long> {
    long countByEventType(FileHistoryType eventType);

    @Query("SELECT COUNT(fhl) FROM FileHistoryLog fhl WHERE fhl.file.uuid = :uuid AND fhl.eventType = :eventType")
    long countByFileAndType(String uuid, FileHistoryType eventType);

    @Query("SELECT fhl FROM FileHistoryLog fhl WHERE fhl.file.uuid = :uuid ORDER BY fhl.eventDate DESC")
    List<FileHistoryLog> findByFileUuidOrderByEventDateDesc(String uuid);

    @Modifying
    @Transactional
    @Query("DELETE FROM FileHistoryLog fhl WHERE fhl.file.id = :fileId")
    void deleteByFileId(Long fileId);
}
