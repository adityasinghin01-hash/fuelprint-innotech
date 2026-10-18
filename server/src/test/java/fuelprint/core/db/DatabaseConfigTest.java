package fuelprint.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import fuelprint.core.CoreSpringTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseConfigTest extends CoreSpringTest {

    @Autowired DataSource dataSource;
    @Autowired DataDirectory dataDirectory;
    @Autowired JdbcTemplate jdbc;

    @Test
    void everyPooledConnectionHasWalFullSyncForeignKeysAndSecureDelete() throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < DatabaseConfig.POOL_SIZE; i++) {
                Connection connection = dataSource.getConnection();
                held.add(connection);
                assertThat(pragma(connection, "journal_mode")).isEqualToIgnoringCase("wal");
                assertThat(pragma(connection, "synchronous")).isEqualTo("2"); // FULL
                assertThat(pragma(connection, "foreign_keys")).isEqualTo("1");
                assertThat(pragma(connection, "secure_delete")).as("deleted rows are overwritten").isEqualTo("1");
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    @Test
    void theDatabaseLivesInTheConfiguredDataFolder() {
        assertThat(dataDirectory.root()).isEqualTo(DATA_DIR.toAbsolutePath().normalize());
        assertThat(Files.isRegularFile(dataDirectory.databaseFile())).isTrue();
        assertThat(dataDirectory.blobFolder()).isEqualTo(dataDirectory.root().resolve("blobs"));
    }

    @Test
    void coreMigrationIsApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).contains("001");
        assertThat(jdbc.queryForList("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')", String.class))
                .contains("records", "events", "commands", "latest_records");
    }

    @Test
    void relativeDataDirIsResolvedAgainstTheRepositoryRoot(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve("contracts"));
        Files.writeString(repo.resolve("contracts").resolve("AGREEMENT.md"), "x");
        Files.createDirectories(repo.resolve("server"));

        assertThat(DataDirectory.resolve("data/runtime", repo.resolve("server")))
                .isEqualTo(repo.resolve("data/runtime"));
        assertThat(DataDirectory.resolve("data/runtime", repo)).isEqualTo(repo.resolve("data/runtime"));
        Path absolute = repo.resolve("elsewhere").toAbsolutePath();
        assertThat(DataDirectory.resolve(absolute.toString(), repo)).isEqualTo(absolute);
    }

    private static String pragma(Connection connection, String name) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void deletedTextIsOverwrittenNotLeftInTheFile() throws Exception {
        String secret = "PHONE-SECRET-" + java.util.UUID.randomUUID();
        Path file = DATA_DIR.resolve("secure-delete-check.db");
        try (Connection connection = DatabaseConfig.create(new DataDirectory(DATA_DIR.resolve("sd"))).getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE contact (v TEXT)");
                statement.execute("INSERT INTO contact VALUES ('" + secret + "')");
                statement.execute("DELETE FROM contact");
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }
        byte[] bytes = Files.readAllBytes(DATA_DIR.resolve("sd").resolve("fuelprint.db"));
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        Files.deleteIfExists(file);
    }
}
