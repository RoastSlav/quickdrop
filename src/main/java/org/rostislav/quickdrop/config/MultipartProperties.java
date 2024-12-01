package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@RefreshScope
@Component
public class MultipartProperties {
    private final ApplicationSettingsRepository applicationSettingsRepository;

    public MultipartProperties(ApplicationSettingsRepository applicationSettingsRepository) {
        this.applicationSettingsRepository = applicationSettingsRepository;
    }

    public String getMaxFileSize() {
        return "" + applicationSettingsRepository.findById(1L).orElseThrow().getMaxFileSize();
    }
}
