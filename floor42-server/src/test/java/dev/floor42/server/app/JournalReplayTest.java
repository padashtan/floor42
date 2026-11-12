package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.journal.JournalException;
import dev.floor42.server.journal.SqliteJournal;
import dev.floor42.server.journal.TestJournals;
import dev.floor42.server.journal.Unreadable;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D14: what starts with rows left out, and what still stops the start (ADR-0042). */
class JournalReplayTest {

    @TempDir
    Path dir;

    @Test
    void theDaemonStartsWithoutTheRowsItCannotReadAndSaysSo() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        journal.append(List.of(TestJournals.created("M-1")));
        TestJournals.insertRaw(journal, "MissionCreated", "{broken", "2026-10-31T10:00:00Z", "M-2", 1);
        // Builds on the row left out: cannot be applied either, and is left out too.
        journal.append(List.of(TestJournals.completed("M-2")));
        journal.append(List.of(TestJournals.completed("M-1")));

        OfficeService office = new OfficeService(journal, Clock.systemUTC());

        assertThat(office.unreadable())
                .extracting(Unreadable::sequence, Unreadable::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, Unreadable.Reason.CORRUPT),
                        org.assertj.core.groups.Tuple.tuple(3L, Unreadable.Reason.NOT_APPLICABLE));
        assertThat(office.unreadable().get(1).detail()).contains("after an earlier event was left out");
        MissionStatus status =
                office.query(c -> c.missions().all().iterator().next().status());
        assertThat(status).isEqualTo(MissionStatus.COMPLETED);
        // The company goes on: new commands are applied and stored as before.
        office.execute(c -> c.missions()
                .create(office.context(Actor.director(), Origin.REAL), "next", "o", "C:/repo", Workflow.BUILT_IN));
        assertThat(journal.replay().entries()).hasSize(5);
    }

    @Test
    void aMissionWhoseCreationIsLeftOutKeepsItsNumberFromNewMissions() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        journal.append(List.of(TestJournals.created("M-1")));
        // Written by a newer Floor42 (a field this version does not know): left out, its number kept (ADR-0051).
        TestJournals.insertRaw(
                journal,
                "MissionCreated",
                "{\"mission\":\"M-2\",\"title\":\"t\",\"objective\":\"o\",\"workspace\":null,\"later\":1}",
                "2026-10-31T10:00:00Z",
                "M-2",
                1);

        OfficeService office = new OfficeService(journal, Clock.systemUTC());

        assertThat(office.unreadable()).extracting(Unreadable::mission).containsExactly("M-2");
        var created = office.execute(c -> c.missions()
                .create(office.context(Actor.director(), Origin.REAL), "next", "o", "C:/repo", Workflow.BUILT_IN));
        assertThat(created.getFirst().mission().value()).isEqualTo("M-3");
    }

    @Test
    void aTicketWhoseCreationIsLeftOutKeepsItsNumberFromNewTickets() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        journal.append(List.of(TestJournals.created("M-1")));
        journal.append(List.of(TestJournals.envelope(
                "M-1",
                new dev.floor42.domain.event.TicketEvent.TicketCreated(
                        new dev.floor42.domain.TicketId("T-1"),
                        new dev.floor42.domain.MissionId("M-1"),
                        "one",
                        "",
                        dev.floor42.domain.Priority.NORMAL,
                        List.of(),
                        List.of("c"),
                        dev.floor42.domain.TicketKind.WORK))));
        // Written by a newer Floor42 (a field this version does not know): left out, its number kept.
        TestJournals.insertRaw(
                journal,
                "TicketCreated",
                "{\"ticket\":\"T-2\",\"mission\":\"M-1\",\"title\":\"t\",\"later\":1}",
                "2026-10-31T10:00:00Z",
                "M-1",
                1);

        OfficeService office = new OfficeService(journal, Clock.systemUTC());

        assertThat(office.unreadable()).hasSize(1);
        var created = office.execute(c -> c.tickets()
                .create(
                        office.context(Actor.director(), Origin.REAL),
                        new dev.floor42.domain.MissionId("M-1"),
                        "next",
                        "",
                        dev.floor42.domain.Priority.NORMAL,
                        List.of(),
                        List.of("c")));
        assertThat(((dev.floor42.domain.event.TicketEvent.TicketCreated)
                                created.getFirst().event())
                        .ticket()
                        .value())
                .isEqualTo("T-3");
    }

    @Test
    void aMalformedMissionIdOfARowDoesNotStopItsTicketNumberFromBeingKept() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        journal.append(List.of(TestJournals.created("M-1")));
        TestJournals.insertRaw(
                journal,
                "TicketCreated",
                "{\"ticket\":\"T-7\",\"later\":1}",
                "2026-10-31T10:00:00Z",
                "not a valid id",
                1);

        OfficeService office = new OfficeService(journal, Clock.systemUTC());

        assertThat(office.unreadable()).hasSize(1);
        var created = office.execute(c -> c.tickets()
                .create(
                        office.context(Actor.director(), Origin.REAL),
                        new dev.floor42.domain.MissionId("M-1"),
                        "next",
                        "",
                        dev.floor42.domain.Priority.NORMAL,
                        List.of(),
                        List.of("c")));
        assertThat(((dev.floor42.domain.event.TicketEvent.TicketCreated)
                                created.getFirst().event())
                        .ticket()
                        .value())
                .isEqualTo("T-8");
    }

    @Test
    void anEventThatCannotBeAppliedWhenNothingWasLeftOutStopsTheStart() throws Exception {
        SqliteJournal journal = TestJournals.open(dir.resolve("floor42.db"));
        journal.append(List.of(TestJournals.created("M-1")));
        // Read, but names a mission that never existed: the rules that applied it are not the ones that wrote it.
        journal.append(List.of(TestJournals.completed("M-9")));

        assertThatThrownBy(() -> new OfficeService(journal, Clock.systemUTC()))
                .isInstanceOf(JournalException.class)
                .hasMessageContaining("Event #2 (MissionCompleted)")
                .hasMessageContaining("does not start")
                .hasMessageContaining("docs/backups.md")
                .hasMessageNotContaining("done");
    }
}
