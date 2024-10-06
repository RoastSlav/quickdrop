package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.model.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {
    @Query("SELECT f FROM FileEntity f WHERE f.uuid = :uuid")
    public Optional<FileEntity> findByUUID(@Param("uuid") String uuid);
}
