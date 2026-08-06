package org.rostislav.quickdrop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exits the JVM after a delay, used after a database restore — SQLite's connection pool can't
 * safely hot-swap the underlying file, so the restored database only takes effect from a fresh
 * process. Relies on an external supervisor (e.g. Docker's {@code restart: unless-stopped}) to
 * bring the process back; a bare {@code java -jar} deployment stays stopped.
 */
@Component
public class ProcessExitRestartTrigger implements RestartTrigger {
    private static final Logger logger = LoggerFactory.getLogger(ProcessExitRestartTrigger.class);

    @Override
    public void scheduleRestart(long delayMillis) {
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.info("Restarting after database restore");
            System.exit(0);
        }, "restart-trigger");
        restartThread.setDaemon(true);
        restartThread.start();
    }
}
