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
    void everyPooledConnectionHasWalFullSyncAndForeignKeys() throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < DatabaseConfig.POOL_SIZE; i++) {
                Connection connection = dataSource.getConnection();
                held.add(connection);
                assertThat(pragma(connection, "journal_mode")).isEqualToIgnoringCase("wal");
                assertThat(pragma(connection, "synchronous")).isEqualTo("2"); // FULL
                assertThat(pragma(connection, "foreign_keys")).isEqualTo("1");
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
}
