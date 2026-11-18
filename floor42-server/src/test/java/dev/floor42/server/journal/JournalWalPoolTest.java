package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The stop on the daemon's real pool (Agroal): idle pooled connections must not keep the journal in WAL mode. */
class JournalWalPoolTest {

    @TempDir
    Path dir;

    @Test
    void aStopWithIdlePooledConnectionsStillLeavesARollbackJournal() throws Exception {
        Path file = dir.resolve("floor42.db");
        try (AgroalDataSource pool = AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration(cp -> cp.maxSize(20)
                        .connectionFactoryConfiguration(
                                cf -> cf.jdbcUrl("jdbc:sqlite:" + file + "?transaction_mode=IMMEDIATE")
                                        .connectionProviderClassName("org.sqlite.JDBC"))))) {
            Flyway.configure()
                    .dataSource(pool)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            SqliteJournal journal = new SqliteJournal();
            journal.dataSource = pool;
            try (Connection c = pool.getConnection()) {
                JournalConnections.useWal(c);
            }
            for (int i = 0; i < 20; i++) journal.append(List.of(TestJournals.created("M-" + i)));
            // Many connections idle in the pool, each having read the journal.
            List<Connection> held = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                Connection c = pool.getConnection();
                c.createStatement()
                        .executeQuery("SELECT count(*) FROM event_journal")
                        .close();
                held.add(c);
            }
            for (Connection c : held) c.close();
            assertThat(dir.resolve("floor42.db-wal")).exists();

            journal.leaveWal();

            assertThat(dir.resolve("floor42.db-wal")).doesNotExist();
            assertThat(dir.resolve("floor42.db-shm")).doesNotExist();
            byte[] header = Files.readAllBytes(file);
            assertThat(header[18]).isEqualTo((byte) 1);
            assertThat(header[19]).isEqualTo((byte) 1);
        }
    }
}
