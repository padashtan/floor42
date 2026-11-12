package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionUnarchived;
import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Archiving a closed mission (ADR-0054): only once it is over, by the director, recorded as one event with its origin and
 * actor; a view of the director's that changes neither the status nor the outcome, and that a replay gives back.
 */
@ExtendWith(GoldenFixtureExtension.class)
class MissionArchiveTest {

    final Fixture f = new Fixture(Origin.REAL);
    /** Simulated: its missions run tickets and sessions without a repository. */
    final Fixture s = new Fixture();

    private MissionId realMission() {
        return (MissionId) ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Archive me",
                                        "Something to put away",
                                        "C:/repo",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
    }

    private Mission mission(MissionId m) {
        return f.company.missions().find(m).orElseThrow();
    }

    @Test
    void onlyAClosedMissionIsArchivedAndByTheDirectorOnly() {
        MissionId m = realMission();
        assertThatThrownBy(() -> f.company.missions().archive(f.director(), m))
                .extracting("code")
                .isEqualTo("MISSION_STILL_OPEN");
        assertThat(f.company.missions().refusalToArchive(f.director(), m))
                .map(Refused::code)
                .contains("MISSION_STILL_OPEN");

        f.record(f.company.missions().cancel(f.director(), m, "not needed"));
        assertThatThrownBy(() -> f.company.missions().archive(f.agent(CHRIS), m))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThatThrownBy(() -> f.company.missions().archive(f.system(), m))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
        assertThat(f.company.missions().refusalToArchive(f.director(), m)).isEmpty();

        List<EventEnvelope> archived = f.record(f.company.missions().archive(f.director(), m));
        assertThat(archived).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo(new MissionArchived(m));
            assertThat(e.origin()).isEqualTo(Origin.REAL);
            assertThat(e.actor()).isEqualTo(Actor.director());
            assertThat(e.mission()).isEqualTo(m);
        });
        assertThat(mission(m).archived()).isTrue();
        assertThat(mission(m).status()).isEqualTo(MissionStatus.CANCELLED);
        assertThat(mission(m).outcome()).isEqualTo("not needed");
        assertThat(f.company.missions().archive(f.director(), m))
                .as("archived again: nothing more")
                .isEmpty();
    }

    @Test
    void restoringBringsItBackUnchanged() {
        MissionId m = realMission();
        assertThat(f.company.missions().unarchive(f.director(), m))
                .as("a mission never archived: nothing to restore")
                .isEmpty();
        f.record(f.company.missions().cancel(f.director(), m, "paused for good"));
        f.record(f.company.missions().archive(f.director(), m));
        assertThatThrownBy(() -> f.company.missions().unarchive(f.agent(CHRIS), m))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");

        List<EventEnvelope> restored = f.record(f.company.missions().unarchive(f.director(), m));
        assertThat(restored).singleElement().satisfies(e -> {
            assertThat(e.event()).isEqualTo(new MissionUnarchived(m));
            assertThat(e.actor()).isEqualTo(Actor.director());
            assertThat(e.origin()).isEqualTo(Origin.REAL);
        });
        assertThat(mission(m).archived()).isFalse();
        assertThat(mission(m).status()).isEqualTo(MissionStatus.CANCELLED);
        assertThat(mission(m).outcome()).isEqualTo("paused for good");
        assertThat(f.company.missions().unarchive(f.director(), m)).isEmpty();
    }

    @Test
    void aSessionStillRunningOnACancelledMissionHoldsTheArchive() {
        MissionId m = s.runningMission();
        TicketId t = s.firstTicket(m);
        s.record(s.company.sessions().start(s.system(), "s-1", t, CHRIS, "claude-sonnet-5-5", "2.1"));
        // Cancelling stops the session's process; until its end is recorded the session is still running.
        s.record(s.company.missions().cancel(s.director(), m, "stop"));
        assertThatThrownBy(() -> s.company.missions().archive(s.director(), m))
                .extracting("code")
                .isEqualTo("SESSION_RUNNING");

        s.record(s.company.sessions().end(s.system(), "s-1", true, "interrupted", null, null, null, null));
        s.record(s.company.missions().archive(s.director(), m));
        assertThat(s.company.missions().find(m).orElseThrow().archived()).isTrue();
    }

    @Test
    void noRetrospectiveIsConvenedOnAnArchivedMissionAndAnOpenOneHoldsTheArchive() {
        MissionId m = s.runningMission();
        TicketId t = s.firstTicket(m);
        s.record(s.company.sessions().start(s.system(), "s-1", t, CHRIS, "claude-sonnet-5-5", "2.1"));
        s.record(s.company.sessions().end(s.system(), "s-1", false, "completed", 3, 10L, 10L, 0.02));
        s.record(s.company.tickets().cancel(s.director(), t, "not needed"));
        s.record(s.company.missions().complete(s.director(), m, "enough"));

        s.record(s.company.retros().open(s.system(), m, CHRIS, List.of(CHRIS), "facts"));
        assertThatThrownBy(() -> s.company.missions().archive(s.director(), m))
                .extracting("code")
                .isEqualTo("RETRO_UNDER_WAY");
        s.record(s.company.retros().closeNow(s.director(), m));
        s.record(s.company.missions().archive(s.director(), m));

        // A second mission, archived before Floor42 convened its retrospective: none is convened, no session starts.
        MissionId other = s.runningMission();
        TicketId o = s.firstTicket(other);
        s.record(s.company.sessions().start(s.system(), "s-2", o, CHRIS, "claude-sonnet-5-5", "2.1"));
        s.record(s.company.sessions().end(s.system(), "s-2", false, "completed", 3, 10L, 10L, 0.02));
        s.record(s.company.missions().cancel(s.director(), other, "enough"));
        s.record(s.company.missions().archive(s.director(), other));
        assertThatThrownBy(() -> s.company.retros().open(s.system(), other, CHRIS, List.of(CHRIS), "facts"))
                .extracting("code")
                .isEqualTo("MISSION_ARCHIVED");
        assertThatThrownBy(() -> s.company.sessions().start(s.system(), "s-3", o, CHRIS, "claude-sonnet-5-5", "2.1"))
                .extracting("code")
                .isEqualTo("MISSION_ARCHIVED");
    }

    @Test
    void filingAMissionAwayIsNoActivityOnIt() {
        MissionId m = realMission();
        f.record(f.company.missions().cancel(f.director(), m, "over"));
        java.time.Instant before = f.company.queries().lastActivity(m).orElseThrow();

        f.record(f.company.missions().archive(f.director(), m));
        assertThat(f.company.queries().lastActivity(m)).contains(before);
        f.record(f.company.missions().unarchive(f.director(), m));
        assertThat(f.company.queries().lastActivity(m)).contains(before);
        assertThat(Company.replay(f.journal).queries().lastActivity(m)).contains(before);
    }

    @Test
    void theJournalReplaysToTheSameArchives() {
        MissionId kept = realMission();
        MissionId away = realMission();
        MissionId back = realMission();
        for (MissionId m : List.of(kept, away, back))
            f.record(f.company.missions().cancel(f.director(), m, "over"));
        f.record(f.company.missions().archive(f.director(), away));
        f.record(f.company.missions().archive(f.director(), back));
        f.record(f.company.missions().unarchive(f.director(), back));

        Company replayed = Company.replay(f.journal);
        assertThat(replayed.missions().all().stream().filter(Mission::archived).map(Mission::id))
                .containsExactly(away);
        // A journal written before archives existed replays as it did: every mission listed.
        Company before = Company.replay(f.journal.stream()
                .filter(e -> !(e.event() instanceof MissionArchived) && !(e.event() instanceof MissionUnarchived))
                .toList());
        assertThat(before.missions().all()).hasSize(3).noneMatch(Mission::archived);
    }
}
