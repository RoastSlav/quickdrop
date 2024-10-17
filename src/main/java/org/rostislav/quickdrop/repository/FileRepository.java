package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.model.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    @Query("SELECT f FROM FileEntity f WHERE f.uuid = :uuid")
    Optional<FileEntity> findByUUID(@Param("uuid") String uuid);

    @Query("SELECT f FROM FileEntity f WHERE f.keepIndefinitely = false AND f.uploadDate < :thresholdDate")
    List<FileEntity> getFilesForDeletion(@Param("thresholdDate") LocalDate thresholdDate);

    @Query("SELECT f FROM FileEntity f WHERE f.name LIKE %:searchString% OR f.description LIKE %:searchString% OR f.uuid LIKE %:searchString%")
    List<FileEntity> searchFiles(@Param("searchString") String searchString);
}
