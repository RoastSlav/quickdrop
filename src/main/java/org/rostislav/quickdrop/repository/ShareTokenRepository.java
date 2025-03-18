package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.ShareTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShareTokenRepository extends JpaRepository<ShareTokenEntity, Long> {
    @Query("SELECT s FROM ShareTokenEntity s WHERE s.shareToken = :shareToken")
    ShareTokenEntity getShareTokenEntityByToken(String shareToken);

    @Query("SELECT s FROM ShareTokenEntity s WHERE s.tokenExpirationDate < CURRENT_DATE OR s.numberOfAllowedDownloads = 0")
    List<ShareTokenEntity> getShareTokenEntitiesForDeletion();
}
