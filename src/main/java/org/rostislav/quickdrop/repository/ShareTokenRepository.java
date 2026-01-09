package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.FileEntity;
import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShareTokenRepository extends JpaRepository<ShareTokenEntity, Long> {
    Optional<ShareTokenEntity> findByShareToken(String shareToken);

    boolean existsByShareToken(String shareToken);

    void deleteAllByFile(FileEntity fileEntity);

    @Query("SELECT s FROM ShareTokenEntity s WHERE s.tokenExpirationDate < CURRENT_DATE OR s.numberOfAllowedDownloads = 0")
    List<ShareTokenEntity> getShareTokenEntitiesForDeletion();

    Optional<ShareTokenEntity> findFirstByFileAndTokenExpirationDateIsNullAndNumberOfAllowedDownloadsIsNull(FileEntity file);
}
