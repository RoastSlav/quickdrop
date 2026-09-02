package org.rostislav.quickdrop.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * The directories the app writes to, all of them under {@code db/} or {@code files/} so that
 * two volumes (plus {@code log/}) cover everything.
 *
 * <p>Branding sits under {@code db/} rather than next to the uploads: it is small, read on
 * every page render, and {@code db/} is the volume most likely to be on fast local storage,
 * while {@code files/} is routinely a NAS mount or unused entirely on a remote backend.
 */
public final class AppPaths {
    private static final Logger logger = LoggerFactory.getLogger(AppPaths.class);

    public static final Path BACKUPS = Path.of("db", "backups");
    public static final Path BRANDING = Path.of("db", "branding");
    public static final Path REPUTATION_FEEDS = Path.of("files", "reputation-feeds");

    /** Where the custom logo lived from v1.5.1 through v1.5.3. */
    private static final Path RELEASED_BRANDING_DIR = Path.of("branding");

    private AppPaths() {
    }

    /**
     * Moves an uploaded logo out of the top-level {@code branding/} directory a v1.5.x install
     * would have. Backups and reputation feeds never shipped in a release, so nothing upgrading
     * from one has those directories to migrate.
     */
    public static void migrateReleasedBrandingDirectory() {
        if (!Files.isDirectory(RELEASED_BRANDING_DIR)) {
            return;
        }
        try (Stream<Path> files = Files.list(RELEASED_BRANDING_DIR)) {
            int moved = 0;
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Files.createDirectories(BRANDING);
                Path destination = BRANDING.resolve(source.getFileName().toString());
                if (Files.exists(destination)) {
                    continue;
                }
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
            if (moved > 0) {
                logger.info("Moved {} branding file(s) into {}; the branding volume can be removed", moved, BRANDING);
            }
        } catch (IOException e) {
            logger.error("Failed to move {} into {}; leaving it in place", RELEASED_BRANDING_DIR, BRANDING, e);
        }
    }
}
