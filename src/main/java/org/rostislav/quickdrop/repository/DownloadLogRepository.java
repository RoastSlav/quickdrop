package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.DownloadLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DownloadLogRepository extends JpaRepository<DownloadLog, Long> {
    @Query("SELECT COUNT(dl) FROM DownloadLog dl")
    long countAllDownloads();

    @Query("SELECT COUNT(dl) FROM DownloadLog dl WHERE dl.file.id = :fileId")
    long countDownloadsByFileId(Long fileId);

    List<DownloadLog> findByFileId(Long fileId);
}
