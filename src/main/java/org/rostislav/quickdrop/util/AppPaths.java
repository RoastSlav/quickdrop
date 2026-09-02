package org.rostislav.quickdrop.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
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

    /** Where each of these lived before they were folded into {@code db/} and {@code files/}. */
    private static final Map<Path, Path> LEGACY_LOCATIONS = Map.of(
            Path.of("db-backups"), BACKUPS,
            Path.of("branding"), BRANDING,
            Path.of("reputation-feeds"), REPUTATION_FEEDS);

    private AppPaths() {
    }

    /**
     * Moves anything left in the old top-level directories into their new homes, so an instance
     * upgrading with those volumes still mounted keeps its backups and its custom logo.
     */
    public static void migrateLegacyDirectories() {
        LEGACY_LOCATIONS.forEach(AppPaths::migrate);
    }

    private static void migrate(Path legacy, Path target) {
        if (!Files.isDirectory(legacy)) {
            return;
        }
        try (Stream<Path> files = Files.list(legacy)) {
            int moved = 0;
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Files.createDirectories(target);
                Path destination = target.resolve(source.getFileName().toString());
                if (Files.exists(destination)) {
                    continue;
                }
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                moved++;
            }
            if (moved > 0) {
                logger.info("Moved {} file(s) from {} to {}; that volume can be removed", moved, legacy, target);
            }
        } catch (IOException e) {
            logger.error("Failed to move {} into {}; leaving it in place", legacy, target, e);
        }
    }
}
