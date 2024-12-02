package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.FileEntity;
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

    @Query("SELECT f FROM FileEntity f WHERE (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    List<FileEntity> searchFiles(@Param("searchString") String searchString);

    @Query("SELECT f FROM FileEntity f WHERE f.hidden = false")
    List<FileEntity> findAllNotHiddenFiles();

    @Query("SELECT SUM(f.size) FROM FileEntity f")
    Long totalFileSizeForAllFiles();

    @Query("SELECT f FROM FileEntity f WHERE f.hidden = false AND (LOWER(f.name) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.description) LIKE LOWER(CONCAT('%', :searchString, '%')) OR LOWER(f.uuid) LIKE LOWER(CONCAT('%', :searchString, '%')))")
    List<FileEntity> searchNotHiddenFiles(@Param("searchString") String query);
}
