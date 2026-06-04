package org.rostislav.quickdrop.storage;

import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link LocalStorageService} and {@link S3StorageService} beans with
 * suppliers that read live settings from {@link ApplicationSettingsService}. Using
 * suppliers rather than direct value injection means storage paths and S3 credentials
 * are re-read on every call, so admin changes to settings take effect immediately.
 */
@Configuration
public class StorageConfiguration {

    @Bean
    public LocalStorageService localStorageService(ApplicationSettingsService settings) {
        return new LocalStorageService(settings::getFileStoragePath);
    }

    @Bean
    public S3StorageService s3StorageService(ApplicationSettingsService settings) {
        return new S3StorageService(() -> new S3StorageService.S3Config(
                settings.getS3Endpoint(),
                settings.getS3Bucket(),
                settings.getS3Region(),
                settings.getS3AccessKey(),
                settings.getS3SecretKey(),
                settings.isS3PathStyle(),
                settings.getS3KeyPrefix()
        ));
    }
}
