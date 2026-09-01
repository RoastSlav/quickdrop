package org.rostislav.quickdrop.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit test -- no Spring context. The post-processor runs before one exists, and its
 * whole job is to behave sanely against a database that may be missing, unmigrated or
 * holding a nonsense value, which is easiest to set up by hand.
 */
class LogStoragePathEnvironmentPostProcessorTest {

    private final LogStoragePathEnvironmentPostProcessor processor =
            new LogStoragePathEnvironmentPostProcessor(new DeferredLogs());
    @TempDir
    Path tempDir;

    // -- reading the setting ---------------------------------------------------

    @Test
    void readsConfiguredPathFromTheSettingsRow() throws Exception {
        Path database = seedDatabase("custom-logs");

        assertEquals(Optional.of("custom-logs"), processor.readConfiguredDirectory(jdbcUrl(database)));
    }

    @Test
    void ignoresTheDatabaseQueryStringWhenLocatingTheFile() throws Exception {
        Path database = seedDatabase("custom-logs");

        assertEquals(Optional.of("custom-logs"),
                processor.readConfiguredDirectory(jdbcUrl(database) + "?journal_mode=WAL&busy_timeout=5000"));
    }

    @Test
    void fallsBackWhenTheDatabaseFileDoesNotExistYet() {
        Path missing = tempDir.resolve("not-created-yet.db");

        assertEquals(Optional.empty(), processor.readConfiguredDirectory(jdbcUrl(missing)));
        assertFalse(Files.exists(missing), "looking up the setting must not create the database file");
    }

    @Test
    void fallsBackWhenTheSettingsTableDoesNotExistYet() throws Exception {
        // First boot of an existing install: the file is there, Flyway hasn't run yet.
        Path database = tempDir.resolve("unmigrated.db");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE flyway_schema_history (installed_rank INTEGER)");
        }

        assertEquals(Optional.empty(), processor.readConfiguredDirectory(jdbcUrl(database)));
    }

    @Test
    void fallsBackWhenTheStoredValueIsBlank() throws Exception {
        assertEquals(Optional.empty(), processor.readConfiguredDirectory(jdbcUrl(seedDatabase("   "))));
    }

    @Test
    void fallsBackWhenTheStoredValueIsNull() throws Exception {
        assertEquals(Optional.empty(), processor.readConfiguredDirectory(jdbcUrl(seedDatabase(null))));
    }

    @Test
    void fallsBackForANonFileDatabaseUrl() {
        assertEquals(Optional.empty(), processor.readConfiguredDirectory(null));
        assertEquals(Optional.empty(), processor.readConfiguredDirectory("jdbc:postgresql://localhost/quickdrop"));
        assertEquals(Optional.empty(), processor.readConfiguredDirectory("jdbc:sqlite::memory:"));
    }

    // -- rejecting an unusable value -------------------------------------------

    @Test
    void rejectsAPathThatEscapesTheApplicationDirectory() {
        assertFalse(processor.isUsable(".."));
        assertFalse(processor.isUsable("../escaped"));
        assertFalse(processor.isUsable("log/../../escaped"));
    }

    @Test
    void acceptsRelativeAndAbsolutePaths() {
        // Absolute paths are legitimate -- Docker deployments mount /app/log.
        assertTrue(processor.isUsable("log"));
        assertTrue(processor.isUsable("var/log/quickdrop"));
        assertTrue(processor.isUsable(tempDir.toString()));
    }

    // -- contributing the property ---------------------------------------------

    @Test
    void pointsLoggingFileNameAtTheConfiguredDirectoryAndCreatesIt() throws Exception {
        Path logDirectory = tempDir.resolve("configured-logs");
        Path database = seedDatabase(logDirectory.toString());
        StandardEnvironment environment = environmentWithDatabase(database);

        processor.postProcessEnvironment(environment, null);

        assertEquals(logDirectory.resolve("quickdrop.log").toString(),
                Path.of(environment.getProperty("logging.file.name")).toString());
        assertTrue(Files.isDirectory(logDirectory), "the configured log directory must be created");
    }

    @Test
    void fallsBackToTheDefaultLogFileWhenNothingIsConfigured() {
        StandardEnvironment environment = environmentWithDatabase(tempDir.resolve("absent.db"));

        processor.postProcessEnvironment(environment, null);

        assertEquals("log/quickdrop.log", environment.getProperty("logging.file.name"));
    }

    @Test
    void doesNotOverrideAnExplicitLoggingFileName() throws Exception {
        // A profile, JVM argument or environment variable must still win -- the contributed
        // property is added as the lowest-precedence source.
        StandardEnvironment environment =
                environmentWithDatabase(seedDatabase(tempDir.resolve("configured-logs").toString()));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "explicit", Map.of("logging.file.name", "target/test-data/explicit.log")));

        processor.postProcessEnvironment(environment, null);

        assertEquals("target/test-data/explicit.log", environment.getProperty("logging.file.name"));
    }

    // -- helpers ---------------------------------------------------------------

    private StandardEnvironment environmentWithDatabase(Path database) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "test", Map.of("spring.datasource.url", jdbcUrl(database))));
        return environment;
    }

    private String jdbcUrl(Path database) {
        return "jdbc:sqlite:" + database;
    }

    /**
     * Creates a database holding just enough of {@code app_settings} for the lookup.
     */
    private Path seedDatabase(String logStoragePath) throws Exception {
        Path database = tempDir.resolve("quickdrop.db");
        try (Connection connection = DriverManager.getConnection(jdbcUrl(database));
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE app_settings (id INTEGER PRIMARY KEY, log_storage_path VARCHAR(255))");
            statement.execute("INSERT INTO app_settings (id, log_storage_path) VALUES (1, "
                    + (logStoragePath == null ? "NULL" : "'" + logStoragePath + "'") + ")");
        }
        return database;
    }
}
