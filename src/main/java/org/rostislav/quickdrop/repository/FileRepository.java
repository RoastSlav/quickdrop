package org.rostislav.quickdrop.repository;

import jakarta.transaction.Transactional;
import org.rostislav.quickdrop.model.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    @Query("SELECT f FROM FileEntity f WHERE f.uuid = :uuid")
    Optional<FileEntity> findByUUID(@Param("uuid") String uuid);

    //Get files that are not marked to be kept indefinitely and were uploaded more than maxFileAge days ago
    @Modifying
    @Transactional
    @Query("DELETE FROM FileEntity f WHERE f.keepIndefinitely = false AND f.uploadDate < :thresholdDate")
    int deleteOldFiles(@Param("thresholdDate") LocalDate thresholdDate);
}
