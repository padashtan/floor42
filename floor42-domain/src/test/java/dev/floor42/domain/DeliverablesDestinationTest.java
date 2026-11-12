package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where a mission's accepted deliverables land (ADR-0051): the director's folder, recorded once at creation, the copy
 * journaled once before the mission completes, and mission ids that a rollback can never give twice.
 */
class DeliverablesDestinationTest {

    static final String HOME = "C:/work/notes/deliverables";

    final Fixture f = new Fixture(Origin.REAL);

    MissionId mission() {
        return ((MissionCreated) f.record(f.company
                                .missions()
                                .create(f.director(), "Notes", "Write notes", "C:/work/notes", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
    }

    @Test
    void theDirectorsFolderIsRecordedAndSurvivesAReplay() {
        MissionId m = mission();
        f.record(f.company.deliveries().setDestination(f.director(), m, HOME));
        assertThat(f.company.deliveries().destination(m)).contains(HOME);
        assertThat(Company.replay(f.journal).deliveries().destination(m)).contains(HOME);
    }

    @Test
    void aMissionCreatedWithoutAFolderKeepsTodaysBehaviour() {
        MissionId m = mission();
        assertThat(f.company.deliveries().destination(m)).isEmpty();
        assertThat(f.company.deliveries().copy(m)).isEmpty();
        assertThatThrownBy(() -> f.company.deliveries().recordCopy(f.director(), m, HOME + "/M-1", List.of(), 0, false))
                .extracting("code")
                .isEqualTo("NO_DESTINATION");
    }

    @Test
    void onlyTheDirectorSetsAnAbsoluteFolderOnARealOpenMission() {
        MissionId m = mission();
        assertThatThrownBy(() -> f.company.deliveries().setDestination(f.agent(CHRIS), m, HOME))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> f.company.deliveries().setDestination(f.system(), m, HOME))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        for (String bad : List.of("deliverables", "C:/work/notes/../../Windows", "~/x", "C:work")) {
            assertThatThrownBy(() -> f.company.deliveries().setDestination(f.director(), m, bad))
                    .as(bad)
                    .extracting("code")
                    .isEqualTo("INVALID_ARGUMENT");
        }
        Fixture sim = new Fixture(Origin.SIMULATED);
        MissionId s = sim.mission();
        assertThatThrownBy(() -> sim.company.deliveries().setDestination(sim.director(), s, HOME))
                .extracting("code")
                .isEqualTo("SIMULATED_MISSION");
    }

    @Test
    void theCopyIsRecordedOnceInsideTheFolderWithKnownOutcomes() {
        MissionId m = mission();
        f.record(f.company.deliveries().setDestination(f.director(), m, HOME));
        List<DeliveryCopy> files = List.of(
                new DeliveryCopy("dev-1", "notes.md", "notes.md", DeliveryCopy.WRITTEN, null),
                new DeliveryCopy("dev-1", "report.pdf", "report (2).pdf", DeliveryCopy.KEPT_BOTH, null),
                new DeliveryCopy("dev-1", "build.sh", null, DeliveryCopy.LEFT_OUT, "not a document"));
        assertThatThrownBy(() -> f.company.deliveries().recordCopy(f.director(), m, HOME, files, 0, false))
                .as("the folder itself, not the mission's folder in it")
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        assertThatThrownBy(
                        () -> f.company.deliveries().recordCopy(f.director(), m, HOME + "-other/M-1", files, 0, false))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        List<DeliveryCopy> odd = List.of(new DeliveryCopy("dev-1", "x.md", "x.md", "OVERWRITTEN", null));
        assertThatThrownBy(() -> f.company.deliveries().recordCopy(f.director(), m, HOME + "/M-1", odd, 0, false))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");

        f.record(f.company.deliveries().recordCopy(f.director(), m, HOME + "/M-1", files, 2, true));
        DeliveryCopy.Done done = Company.replay(f.journal).deliveries().copy(m).orElseThrow();
        assertThat(done.files()).isEqualTo(files);
        assertThat(done.leftOut()).isEqualTo(2);
        assertThat(done.truncated()).isTrue();
        assertThatThrownBy(() -> f.company.deliveries().recordCopy(f.director(), m, HOME + "/M-1", files, 0, false))
                .extracting("code")
                .isEqualTo("OUTCOME_ALREADY_RECORDED");
    }

    @Test
    void aMissionNumberHeldByARowSetAsideIsNeverGivenAgain() {
        mission();
        MissionId second = mission();
        // A rollback: the row creating the second mission is one this version could not read.
        List<EventEnvelope> readable = new ArrayList<>(f.journal);
        readable.removeIf(e -> second.equals(e.mission())
                && (e.event() instanceof MissionCreated
                        || e.event() instanceof dev.floor42.domain.event.WorkflowEvent.WorkflowPinned));
        Company older = Company.replay(readable);
        older.missions().reserve(second);
        List<EventEnvelope> next =
                older.missions().create(f.director(), "Next", "Another", "C:/work/notes", Workflow.BUILT_IN);
        assertThat(((MissionCreated) next.getFirst().event()).mission()).isEqualTo(new MissionId("M-3"));
    }

    @Test
    void theNextNumberFollowsTheHighestEverCreatedNotTheCount() {
        mission();
        MissionId second = mission();
        List<EventEnvelope> readable = new ArrayList<>(f.journal);
        readable.removeIf(e -> e.mission() != null
                && !e.mission().equals(second)
                && (e.event() instanceof MissionCreated
                        || e.event() instanceof dev.floor42.domain.event.WorkflowEvent.WorkflowPinned));
        Company c = Company.replay(readable);
        List<EventEnvelope> next =
                c.missions().create(f.director(), "Next", "Another", "C:/work/notes", Workflow.BUILT_IN);
        assertThat(((MissionCreated) next.getFirst().event()).mission()).isEqualTo(new MissionId("M-3"));
    }

    @Test
    void twoCreationsOfOneIdStopTheReplayInsteadOfMergingTwoMissions() {
        mission();
        List<EventEnvelope> twice = new ArrayList<>(f.journal);
        twice.add(f.journal.stream()
                .filter(e -> e.event() instanceof MissionCreated)
                .findFirst()
                .orElseThrow());
        assertThatThrownBy(() -> Company.replay(twice)).hasMessageContaining("M-1 is created twice");
    }
}
