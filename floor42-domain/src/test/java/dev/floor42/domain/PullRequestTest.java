package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A pull request is the director's outward action (ADR-0036): never an agent's, never Floor42's own, never simulated. */
@ExtendWith(GoldenFixtureExtension.class)
class PullRequestTest {

    final Fixture f = new Fixture(Origin.REAL);

    MissionId mission() {
        MissionId id = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(f.director(), "Notes", "Write notes", "C:/work/notes", Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        f.record(f.company
                .workshop()
                .prepareWorkspace(f.system(), id, CHRIS, "C:/ws/M-1-chris", "floor42/m-1-chris", "abc"));
        return id;
    }

    @Test
    void theDirectorsPullRequestIsRecordedWithItsChecksAndSurvivesAReplay() {
        MissionId m = mission();
        f.record(f.company
                .deliveries()
                .recordPullRequest(
                        f.director(),
                        m,
                        CHRIS,
                        "https://github.com/acme/notes/pull/7",
                        7,
                        "floor42/m-1-chris",
                        "main",
                        "origin",
                        false));
        f.record(f.company
                .deliveries()
                .recordPullRequestChecks(f.director(), m, 7, List.of("build|pass|SUCCESS", "lint|fail|")));

        Company replayed = Company.replay(f.journal);
        assertThat(replayed.deliveries().pullRequests(m)).singleElement().satisfies(p -> {
            assertThat(p.number()).isEqualTo(7);
            assertThat(p.branch()).isEqualTo("floor42/m-1-chris");
            assertThat(p.workspace()).isEqualTo(CHRIS);
            assertThat(p.checks()).extracting(PullRequest.Check::bucket).containsExactly("pass", "fail");
            assertThat(p.checksAt()).isNotNull();
        });
    }

    @Test
    void anAgentOrOfficeItselfIsRefused() {
        MissionId m = mission();
        assertThatThrownBy(() -> f.company.deliveries().checkPullRequest(f.agent(CHRIS), m))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> f.company
                        .deliveries()
                        .recordPullRequest(
                                f.agent(CHRIS),
                                m,
                                CHRIS,
                                "https://x/pull/1",
                                1,
                                "floor42/m-1-chris",
                                "main",
                                "origin",
                                false))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> f.company
                        .deliveries()
                        .recordPullRequest(
                                f.system(),
                                m,
                                CHRIS,
                                "https://x/pull/1",
                                1,
                                "floor42/m-1-chris",
                                "main",
                                "origin",
                                false))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void aBranchIsNeverItsOwnBaseAndANumberIsRecordedOnce() {
        MissionId m = mission();
        assertThatThrownBy(() -> f.company
                        .deliveries()
                        .recordPullRequest(
                                f.director(), m, null, "https://x/pull/1", 1, "main", "main", "origin", false))
                .extracting("code")
                .isEqualTo("HEAD_IS_BASE");
        f.record(f.company
                .deliveries()
                .recordPullRequest(f.director(), m, null, "https://x/pull/1", 1, "feature", "main", "origin", true));
        assertThatThrownBy(() -> f.company
                        .deliveries()
                        .recordPullRequest(
                                f.director(), m, null, "https://x/pull/1", 1, "feature", "main", "origin", true))
                .extracting("code")
                .isEqualTo("PULL_REQUEST_RECORDED");
    }

    @Test
    void aSimulatedMissionPushesNothing() {
        Fixture sim = new Fixture(Origin.SIMULATED);
        MissionId m = sim.mission();
        assertThatThrownBy(() -> sim.company.deliveries().checkPullRequest(sim.director(), m))
                .extracting("code")
                .isEqualTo("SIMULATED_MISSION");
    }
}
