package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.server.config.DataFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Office's journal is copied, never moved, on Floor42's first start (1.5.0). */
class DataMigrationTest {

    private static final org.sqlite.JDBC SQLITE = new org.sqlite.JDBC();

    @TempDir
    Path home;

    @Test
    void theFormerFolderIsCopiedAndLeftAsItWas() throws Exception {
        Path office = home.resolve(".office");
        Path floor42 = home.resolve(".floor42");
        Files.createDirectories(office.resolve("workspaces").resolve("M-1-chris"));
        Files.createDirectories(floor42);
        Path old = journal(office.resolve(DataFolders.LEGACY_JOURNAL), 3);
        byte[] before = Files.readAllBytes(old);

        var done = DataMigration.run(floor42, office);

        assertThat(done).isPresent();
        assertThat(done.get().events()).isEqualTo(3);
        assertThat(count(floor42.resolve(DataFolders.JOURNAL))).isEqualTo(3);
        assertThat(Files.readAllBytes(old)).isEqualTo(before);
        assertThat(office.resolve("workspaces").resolve("M-1-chris")).isDirectory();
        assertThat(DataFolders.legacy(floor42)).contains(office.toAbsolutePath().normalize());
        assertThat(DataFolders.guarded(floor42))
                .containsExactly(floor42, office.toAbsolutePath().normalize());
        // The copy keeps the append-only triggers.
        add(floor42.resolve(DataFolders.JOURNAL), "e-new");
        try (Connection c = SQLITE.connect("jdbc:sqlite:" + floor42.resolve(DataFolders.JOURNAL), new Properties());
                Statement s = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.executeUpdate("DELETE FROM event_journal"))
                    .hasMessageContaining("append-only");
        }

        // A second start finds its own journal and copies nothing again.
        assertThat(DataMigration.run(floor42, office)).isEmpty();
        assertThat(count(floor42.resolve(DataFolders.JOURNAL))).isEqualTo(4);
    }

    @Test
    void aDataFolderThatStillHoldsOfficeDbIsCopiedInPlace() throws Exception {
        Path data = home.resolve("data");
        Files.createDirectories(data);
        journal(data.resolve(DataFolders.LEGACY_JOURNAL), 2);
        // SQLite may already have opened (and so created) an empty journal: that is no journal.
        Files.createFile(data.resolve(DataFolders.JOURNAL));

        assertThat(DataMigration.run(data, home.resolve("nothing-here"))).isPresent();

        assertThat(count(data.resolve(DataFolders.JOURNAL))).isEqualTo(2);
        assertThat(data.resolve(DataFolders.LEGACY_JOURNAL)).exists();
        // The worktrees are already in this folder: no former folder to look into.
        assertThat(DataFolders.legacy(data)).isEmpty();
    }

    /**
     * Counter-check of 1.8.0: migrated-from-office.properties was read again on every call, so a process of the
     * daemon's OS user could change which folders are guarded and where worktrees are looked for. Now it is read once
     * at start; only the daemon's own migration changes it afterwards.
     */
    @Test
    void theMigrationRecordIsReadOnceAtStart() throws Exception {
        Path data = Files.createDirectories(home.resolve("frozen"));
        Path elsewhere = Files.createDirectories(home.resolve("elsewhere"));
        DataFolders.freeze(data);

        Files.writeString(
                data.resolve(DataFolders.MIGRATION_RECORD),
                "from=" + elsewhere.toString().replace('\\', '/'));

        assertThat(DataFolders.legacy(data))
                .as("written after the start: ignored")
                .isEmpty();
        assertThat(DataFolders.guarded(data)).containsExactly(data);
        DataFolders.recordMigration(data, elsewhere, DataFolders.LEGACY_JOURNAL, 0);
        assertThat(DataFolders.legacy(data))
                .as("the daemon's own migration")
                .contains(elsewhere.toAbsolutePath().normalize());
    }

    @Test
    void aFreshStartCopiesNothing() throws Exception {
        Path data = home.resolve("fresh");
        Files.createDirectories(data);

        assertThat(DataMigration.run(data, home.resolve(".office"))).isEmpty();
        assertThat(DataMigration.run(data, null)).isEmpty();
        assertThat(data.resolve(DataFolders.JOURNAL)).doesNotExist();
        assertThat(DataFolders.guarded(data)).containsExactly(data);
    }

    /** A journal with the real schema (Flyway, as the daemon makes it) and {@code events} rows. */
    static Path journal(Path file, int events) throws Exception {
        org.flywaydb.core.Flyway.configure()
                .dataSource("jdbc:sqlite:" + file, null, null)
                .load()
                .migrate();
        for (int i = 0; i < events; i++) add(file, "e-" + i);
        return file;
    }

    static void add(Path file, String id) throws Exception {
        try (Connection c = SQLITE.connect("jdbc:sqlite:" + file, new Properties());
                PreparedStatement ps = c.prepareStatement("INSERT INTO event_journal (event_id, occurred_at, origin,"
                        + " actor_kind, actor_id, event_type, payload) VALUES (?, '2026-10-31T00:00:00Z', 'REAL',"
                        + " 'SYSTEM', 'office', 'MissionCreated', '{}')")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    static long count(Path file) throws Exception {
        try (Connection c = SQLITE.connect("jdbc:sqlite:" + file, new Properties());
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM event_journal")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
