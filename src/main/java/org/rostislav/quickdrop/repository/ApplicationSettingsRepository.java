package org.rostislav.quickdrop.repository;

import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ApplicationSettingsEntity}.
 *
 * <p>The application maintains exactly one settings row with id = 1.
 * All reads should go through
 * {@link org.rostislav.quickdrop.service.ApplicationSettingsService#getApplicationSettings()}
 * which caches the result; direct repository access should be limited to the
 * service layer and startup initialisation.
 */
public interface ApplicationSettingsRepository extends JpaRepository<ApplicationSettingsEntity, Long> {

}
