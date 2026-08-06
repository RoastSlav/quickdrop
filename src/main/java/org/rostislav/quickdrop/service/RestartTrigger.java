package org.rostislav.quickdrop.service;

/**
 * Seam around the actual restart mechanism, so tests exercising a successful restore don't
 * exit the test JVM. See {@link ProcessExitRestartTrigger} for the real implementation.
 */
public interface RestartTrigger {
    void scheduleRestart(long delayMillis);
}
