package org.rostislav.quickdrop.service;

import org.rostislav.quickdrop.entity.ApplicationSettingsEntity;

/**
 * Published by {@link ApplicationSettingsService#updateApplicationSettings} immediately
 * after the settings row is saved. Listeners use it to propagate the change to whatever
 * they hold that can't simply read {@link ApplicationSettingsService} live at use-time
 * (a cached SDK client, a running scheduled task).
 *
 * <p>Carries the just-saved entity rather than leaving listeners to call
 * {@link ApplicationSettingsService#getApplicationSettings()} themselves: that method's
 * cache is evicted with {@code beforeInvocation = true}, so a read triggered from within
 * this same call graph could repopulate the cache from the database before the eventual
 * {@code save()} — a listener reading through the cache at the wrong moment would see the
 * pre-update row. Reading the entity carried here avoids that race entirely.
 */
public record SettingsChangedEvent(ApplicationSettingsEntity settings) {
}
