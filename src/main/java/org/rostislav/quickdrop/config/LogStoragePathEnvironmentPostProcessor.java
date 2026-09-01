package org.rostislav.quickdrop.config;

import org.apache.commons.logging.Log;
import org.rostislav.quickdrop.service.ApplicationSettingsService;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

/**
 * Points {@code logging.file.name} at the admin-configured "Log Storage Path" setting.
 *
 * <p>Spring Boot initialises the logging system long before the DataSource, JPA and
 * {@link ApplicationSettingsService}'s {@code @PostConstruct} exist, so the value cannot be
 * read through the normal settings service. Instead it is read here, straight out of the
 * SQLite file with plain JDBC, while the {@link ConfigurableEnvironment} is still being
 * prepared -- after {@link ConfigDataEnvironmentPostProcessor} has loaded
 * {@code application.properties} (so {@code spring.datasource.url} is resolvable) and before
 * {@code LoggingApplicationListener} configures Logback.
 *
 * <p>Consequently the setting only takes effect on the next restart, which the settings UI
 * says next to the field.
 *
 * <p>Every failure mode -- no database file yet (first run), no {@code app_settings} table
 * yet (pre-Flyway), a blank or unsafe value, an unreadable file -- falls back to
 * {@link ApplicationSettingsService#DEFAULT_LOG_STORAGE_PATH} rather than failing startup.
 *
 * <p>The property is contributed as the <em>lowest</em>-precedence source, so an explicit
 * {@code logging.file.name} from a profile ({@code application-e2e.properties},
 * {@code application-test.properties}), a JVM argument, an environment variable or the
 * command line still wins -- which is why {@code application.properties} no longer sets it.
 */
public class LogStoragePathEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * File name inside the configured directory. Unchanged from the previous hard-coded value.
     */
    static final String LOG_FILE_NAME = "quickdrop.log";

    static final String LOGGING_FILE_NAME = "logging.file.name";

    private static final String PROPERTY_SOURCE_NAME = "quickdropLogStoragePath";
    private static final String JDBC_SQLITE_PREFIX = "jdbc:sqlite:";
    private static final String SETTINGS_QUERY = "SELECT log_storage_path FROM app_settings WHERE id = 1";

    private final Log log;

    public LogStoragePathEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        // The logging system isn't up yet; a deferred log replays these once it is.
        this.log = logFactory.getLog(LogStoragePathEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String directory = readConfiguredDirectory(environment.getProperty("spring.datasource.url"))
                .filter(this::isUsable)
                .map(this::createDirectory)
                .orElse(ApplicationSettingsService.DEFAULT_LOG_STORAGE_PATH);

        String logFile = directory + "/" + LOG_FILE_NAME;
        environment.getPropertySources().addLast(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(LOGGING_FILE_NAME, logFile)));
        log.info("Application log file: " + logFile);
    }

    /**
     * Reads the setting with plain JDBC. Returns empty -- never throws -- for every reason
     * the value might not be available, including a database that has not been created or
     * migrated yet.
     */
    Optional<String> readConfiguredDirectory(String jdbcUrl) {
        Optional<Path> databaseFile = sqliteFile(jdbcUrl);
        if (databaseFile.isEmpty()) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(databaseFile.get())) {
            // First run: Flyway will create the database a moment from now.
            return Optional.empty();
        }
        // Connect to the parsed path rather than the raw URL: the URL's query parameters are
        // tuning for the pooled application DataSource, not for this one-shot read.
        try (Connection connection = DriverManager.getConnection(JDBC_SQLITE_PREFIX + databaseFile.get());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(SETTINGS_QUERY)) {
            if (result.next()) {
                String value = result.getString(1);
                if (value != null && !value.isBlank()) {
                    return Optional.of(value.trim());
                }
            }
        } catch (Exception e) {
            // Table missing (pre-Flyway), file locked, corrupt, unreadable -- all non-fatal.
            log.warn("Could not read the configured log storage path, falling back to the default: " + e);
        }
        return Optional.empty();
    }

    /**
     * Extracts the database file from a SQLite JDBC URL, or empty when this isn't a plain
     * file-backed SQLite URL (in-memory and resource-backed forms have nothing to read).
     */
    private Optional<Path> sqliteFile(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith(JDBC_SQLITE_PREFIX)) {
            return Optional.empty();
        }
        String path = jdbcUrl.substring(JDBC_SQLITE_PREFIX.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.isBlank() || path.startsWith(":") || path.startsWith("file:")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(path));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Second line of defence behind {@code AdminViewController}'s save-time validation, for a
     * value that reached the database some other way (a hand-edited row, an older build).
     * Only outward traversal is rejected here; absolute paths are legitimate, since Docker
     * deployments mount the log directory at {@code /app/log}.
     */
    boolean isUsable(String directory) {
        try {
            String normalized = Path.of(directory).normalize().toString().replace('\\', '/');
            if (normalized.equals("..") || normalized.startsWith("../")) {
                log.warn("Configured log storage path escapes the application directory, "
                        + "falling back to the default: " + directory);
                return false;
            }
            return true;
        } catch (InvalidPathException e) {
            log.warn("Configured log storage path is not a valid path, falling back to the default: " + directory);
            return false;
        }
    }

    /**
     * Logback would create the directory itself, but only once it first writes; creating it
     * here surfaces a permissions problem as a warning against a named directory instead.
     */
    private String createDirectory(String directory) {
        try {
            Files.createDirectories(Path.of(directory));
        } catch (Exception e) {
            log.warn("Could not create the configured log directory " + directory + ": " + e);
        }
        return directory;
    }
}
