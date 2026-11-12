package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.golden.CompanySnapshot;
import dev.floor42.server.app.JournalReplay;
import dev.floor42.server.app.WorkflowDecider;
import dev.floor42.server.app.WorkflowEquivalence;
import dev.floor42.server.config.DataFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * A one-off proof, off in every build: migrates a COPY of a real Office journal the way the first start of Floor42
 * does, then replays it with the renamed code. Run it on a copy, never on the live folder:
 *
 * <pre>
 * ./mvnw -pl floor42-server test -Dtest=RealJournalReplayTest -Dfloor42.replay.journal=/tmp/copy/office.db
 * </pre>
 *
 * (add {@code -Dfloor42.replay.expect=events,missions} to assert the counts). It prints what it found.
 *
 * <p>The second proof replays a COPY of a Floor42 journal the way the daemon starts (rows it cannot read left out) and
 * prints the SHA-256 of the company's snapshot, the replay's duration and the rows left out: a refactoring of the
 * domain must leave the SHA as it was. Make the copy with SQLite's backup, never by hand on the live file:
 *
 * <pre>
 * ./mvnw -pl floor42-domain,floor42-server test -Dtest=RealJournalReplayTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dfloor42.replay.floor42-journal=/tmp/copy/floor42.db [-Dfloor42.replay.sha=...]
 * </pre>
 */
class RealJournalReplayTest {

    @TempDir
    Path data;

    @Test
    @EnabledIfSystemProperty(named = "floor42.replay.journal", matches = ".+")
    void aCopyOfARealJournalMigratesAndReplays() throws Exception {
        Path copy = Path.of(System.getProperty("floor42.replay.journal"))
                .toAbsolutePath()
                .normalize();
        Path live = Path.of(System.getProperty("user.home"), ".office").toAbsolutePath();
        assertThat(copy.startsWith(live))
                .as("run this on a copy, never on " + live)
                .isFalse();
        assertThat(copy.getFileName().toString()).isEqualTo(DataFolders.LEGACY_JOURNAL);
        byte[] before = Files.readAllBytes(copy);

        var done = DataMigration.run(data, copy.getParent()).orElseThrow();

        EventCodec codec = new EventCodec();
        List<EventEnvelope> events = new ArrayList<>();
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + done.to(), new Properties());
                PreparedStatement ps = c.prepareStatement("SELECT event_id, occurred_at, origin, actor_kind, actor_id,"
                        + " mission_id, authorization_ref, causation_id, event_type, payload FROM event_journal"
                        + " ORDER BY sequence");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String mission = rs.getString("mission_id");
                events.add(new EventEnvelope(
                        rs.getString("event_id"),
                        Instant.parse(rs.getString("occurred_at")),
                        Origin.valueOf(rs.getString("origin")),
                        SqliteJournal.actor(rs.getString("actor_kind"), rs.getString("actor_id")),
                        mission == null ? null : new MissionId(mission),
                        rs.getString("authorization_ref"),
                        rs.getString("causation_id"),
                        codec.decode(rs.getString("event_type"), rs.getString("payload"))));
            }
        }
        Company company = Company.replay(events);
        long legacyActors =
                events.stream().filter(e -> e.actor().id().equals("office")).count();
        long legacyCategories = events.stream()
                .filter(e -> e.event() instanceof dev.floor42.domain.event.ActionEvent.ActionRequested a
                        && a.category() == dev.floor42.domain.ActionCategory.FLOOR42)
                .count();
        System.out.printf(
                "Real journal replay: %d rows copied, %d events decoded and replayed, %d missions, %d agents;"
                        + " legacy system actor left: %d; Floor42 tool requests: %d; copy unchanged: %b%n",
                done.events(),
                events.size(),
                company.missions().all().size(),
                company.staff().all().size(),
                legacyActors,
                legacyCategories,
                java.util.Arrays.equals(before, Files.readAllBytes(copy)));

        assertThat(events).hasSize((int) done.events());
        assertThat(legacyActors).isZero();
        assertThat(Files.readAllBytes(copy)).isEqualTo(before);
        String expect = System.getProperty("floor42.replay.expect");
        if (expect != null && !expect.isBlank()) {
            String[] n = expect.split(",");
            assertThat(events).hasSize(Integer.parseInt(n[0].strip()));
            assertThat(company.missions().all()).hasSize(Integer.parseInt(n[1].strip()));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "floor42.replay.floor42-journal", matches = ".+")
    void aCopyOfAFloor42JournalReplaysToTheSameSnapshot() throws Exception {
        Path copy = Path.of(System.getProperty("floor42.replay.floor42-journal"))
                .toAbsolutePath()
                .normalize();
        Path live = Path.of(System.getProperty("user.home"), ".floor42").toAbsolutePath();
        assertThat(copy.startsWith(live))
                .as("run this on a copy, never on " + live)
                .isFalse();
        byte[] before = Files.readAllBytes(copy);
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + copy);
        SqliteJournal journal = new SqliteJournal();
        journal.dataSource = source;

        long start = System.nanoTime();
        Journal.Read read = journal.replay();
        long decoded = System.nanoTime();
        JournalReplay.Result result = JournalReplay.run(read);
        long applied = System.nanoTime();
        String snapshot = CompanySnapshot.of(result.company());
        String sha = CompanySnapshot.sha256(snapshot);
        // -Dfloor42.replay.snapshot-out=<file> writes the snapshot text, to diff two builds line by line.
        String out = System.getProperty("floor42.replay.snapshot-out");
        if (out != null && !out.isBlank()) Files.writeString(Path.of(out), snapshot);

        Map<Unreadable.Reason, Long> leftOut = result.unreadable().stream()
                .collect(Collectors.groupingBy(
                        Unreadable::reason, () -> new EnumMap<>(Unreadable.Reason.class), Collectors.counting()));
        System.out.printf(
                "Floor42 journal replay: %d rows read, %d events applied, %d left out %s; read %d ms, applied %d ms;"
                        + " snapshot SHA-256 %s%n",
                read.entries().size() + read.unreadable().size(),
                result.company().version(),
                result.unreadable().size(),
                leftOut,
                (decoded - start) / 1_000_000,
                (applied - decoded) / 1_000_000,
                sha);
        // The workflow interpreter, after every event of the real journal, decides as c62612d did (ADR-0046).
        WorkflowEquivalence.Result same = WorkflowEquivalence.of(
                read.entries().stream().map(Journal.Entry::envelope).toList());
        System.out.printf(
                "Workflow equivalence over the journal: %d events, %d decisions compared with c62612d, %d differ%n",
                same.events(), same.comparisons(), same.disagreements().size());
        assertThat(same.disagreements()).isEmpty();
        // Every REAL mission pinned to the built-in workflow decides as unpinned; a newer schema refuses (ADR-0050).
        List<EventEnvelope> envelopes =
                read.entries().stream().map(Journal.Entry::envelope).toList();
        WorkflowEquivalence.Result pinned = WorkflowEquivalence.of(
                envelopes,
                WorkflowDecider.daemon(),
                WorkflowEquivalence.Pins.of(dev.floor42.domain.workflow.Workflow.BUILT_IN),
                WorkflowEquivalence.Expectation.FORMER);
        WorkflowEquivalence.Result refused = WorkflowEquivalence.of(
                envelopes,
                WorkflowDecider.daemon(),
                new WorkflowEquivalence.Pins("{}", 2),
                WorkflowEquivalence.Expectation.REFUSED);
        System.out.printf(
                "Pinned to the built-in workflow: %d REAL missions, %d decisions, %d differ; newer schema: %d differ%n",
                WorkflowEquivalence.realMissions(envelopes),
                pinned.comparisons(),
                pinned.disagreements().size(),
                refused.disagreements().size());
        assertThat(pinned.disagreements()).isEmpty();
        assertThat(refused.disagreements()).isEmpty();
        assertThat(Files.readAllBytes(copy)).isEqualTo(before);
        String expected = System.getProperty("floor42.replay.sha");
        if (expected != null && !expected.isBlank()) assertThat(sha).isEqualTo(expected.strip());
    }
}
