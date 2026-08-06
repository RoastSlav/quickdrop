package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;

/**
 * Published by {@link ApplicationSettingsService#updateApplicationSettings} right after the
 * settings row is saved, for listeners that hold something not read live from
 * {@link ApplicationSettingsService} (a cached SDK client, a running scheduled task).
 *
 * <p>Carries the just-saved entity rather than having listeners call
 * {@link ApplicationSettingsService#getApplicationSettings()} themselves: that method's cache
 * is evicted with {@code beforeInvocation = true}, so a listener reading through it mid-call
 * could repopulate it with the pre-update row before {@code save()} runs.
 */
public record SettingsChangedEvent(ApplicationSettingsEntity settings) {
}
