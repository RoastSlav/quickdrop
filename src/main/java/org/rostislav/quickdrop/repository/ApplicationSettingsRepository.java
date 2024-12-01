package org.rostislav.quickdrop.repository;


import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationSettingsRepository extends JpaRepository<ApplicationSettingsEntity, Long> {

}
