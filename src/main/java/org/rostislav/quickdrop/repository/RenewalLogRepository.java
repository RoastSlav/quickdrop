package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.FileRenewalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RenewalLogRepository extends JpaRepository<FileRenewalLog, Long> {

    @Query("SELECT f FROM FileRenewalLog f WHERE f.file.uuid = :uuid")
    List<FileRenewalLog> findByFileUuid(String uuid);
}
