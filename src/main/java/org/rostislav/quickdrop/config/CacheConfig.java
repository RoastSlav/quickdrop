package org.rostislav.quickdrop.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the application's in-memory cache.
 *
 * <p>A {@link ConcurrentMapCacheManager} is used as a lightweight, zero-dependency
 * cache store. The named caches are:
 * <ul>
 *   <li>{@code publicFiles} — paginated public file listings</li>
 *   <li>{@code adminFiles} — paginated admin file listings with download counts</li>
 *   <li>{@code adminPastes} — paginated admin paste listings with view counts</li>
 *   <li>{@code analytics} — aggregated dashboard metrics</li>
 *   <li>{@code applicationSettings} — the single {@link org.rostislav.quickdrop.entity.ApplicationSettingsEntity} row</li>
 * </ul>
 * All caches are evicted on write operations via {@code @CacheEvict} annotations in
 * the service layer.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Registers the named caches used throughout the application.
     *
     * @return the configured cache manager
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("publicFiles", "adminFiles", "adminPastes", "analytics", "applicationSettings");
    }
}
