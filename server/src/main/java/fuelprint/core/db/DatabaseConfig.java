package fuelprint.core.db;

import java.time.Clock;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite connection setup. Every connection the pool opens gets WAL journaling,
 * {@code synchronous=FULL} and {@code foreign_keys=ON}: the SQLite driver applies these
 * pragmas when it opens the connection.
 */
@Configuration
public class DatabaseConfig {

    /** Small pool: WAL lets readers run beside the one writer. Writes are serialised by the command bus. */
    static final int POOL_SIZE = 4;
    static final int BUSY_TIMEOUT_MS = 10_000;

    @Bean(destroyMethod = "close")
    public DataSource dataSource(DataDirectory dataDirectory) {
        return create(dataDirectory);
    }

    /** Wall clock for {@code *_utc} fields. Tests may replace it. Not the simulation clock. */
    @Bean
    public Clock wallClock() {
        return Clock.systemUTC();
    }

    public static HikariDataSource create(DataDirectory dataDirectory) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(BUSY_TIMEOUT_MS);
        // Take the write lock at BEGIN, so a transaction never fails half-way on lock upgrade.
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqlite);
        sqliteDataSource.setUrl("jdbc:sqlite:" + dataDirectory.databaseFile());

        HikariConfig pool = new HikariConfig();
        pool.setPoolName("fp-sqlite");
        pool.setDataSource(sqliteDataSource);
        pool.setMaximumPoolSize(POOL_SIZE);
        pool.setMinimumIdle(1);
        return new HikariDataSource(pool);
    }
}
