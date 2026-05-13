package org.rostislav.quickdrop.config;

import org.rostislav.quickdrop.repository.ApplicationSettingsRepository;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Provides the current max-file-size value in the string format expected by
 * {@link org.springframework.util.unit.DataSize#parse(CharSequence)}.
 *
 * <p>This component is annotated with {@link RefreshScope} so it participates in
 * Spring Cloud context refreshes triggered when settings are saved. It reads
 * directly from the repository rather than through
 * {@link org.rostislav.quickdrop.service.ApplicationSettingsService} to avoid a
 * circular dependency ({@code ApplicationSettingsService} → {@code MultipartConfig}
 * → {@code MultipartProperties} → {@code ApplicationSettingsService}).
 */
@RefreshScope
@Component
public class MultipartProperties {
    private final ApplicationSettingsRepository applicationSettingsRepository;

    public MultipartProperties(ApplicationSettingsRepository applicationSettingsRepository) {
        this.applicationSettingsRepository = applicationSettingsRepository;
    }

    /**
     * Returns the max file size in bytes as a plain numeric string (e.g. {@code "1073741824"}).
     *
     * @return max file size string parseable by {@link org.springframework.util.unit.DataSize}
     */
    public String getMaxFileSize() {
        return "" + applicationSettingsRepository.findById(1L).orElseThrow().getMaxFileSize();
    }
}
