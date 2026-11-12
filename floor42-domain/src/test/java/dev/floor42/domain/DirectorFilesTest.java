package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director gives a mission files on their own branch, then merges it into the agents' worktrees. */
@ExtendWith(GoldenFixtureExtension.class)
class DirectorFilesTest {

    final Fixture f = new Fixture(Origin.REAL);
    TicketId ticket;

    /** A mission on a repository, Chris at work on its ticket in his own worktree. */
    MissionId mission() {
        MissionId id = ((dev.floor42.domain.event.MissionEvent.MissionCreated) f.record(f.company
                                .missions()
                                .create(
                                        f.director(),
                                        "Write the report",
                                        "A report for a client",
                                        "C:/work/report",
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        ticket = f.ticket(id, "Deliver: the report");
        f.record(f.company.tickets().assign(f.director(), ticket, CHRIS));
        f.record(f.company.missions().submitPlan(f.agent(dev.floor42.domain.Fixture.GALEN), id));
        f.record(f.company.verification().setBudget(f.director(), id, null));
        f.record(f.company.missions().approvePlan(f.director(), id));
        f.record(f.company.tickets().start(f.agent(CHRIS), ticket));
        f.record(f.company
                .workshop()
                .prepareWorkspace(f.system(), id, CHRIS, "C:/ws/M-1-chris", "floor42/m-1-chris", "abc"));
        return id;
    }

    @Test
    void aCleanMergeMovesTheBaseTheAgentsWorkIsMeasuredFrom() {
        MissionId m = mission();
        f.record(f.company.workshop().recordDirectorFiles(f.director(), m, "def", List.of("inputs/brief.pdf")));
        f.record(f.company
                .workshop()
                .recordDirectorFiles(f.director(), m, "deg", List.of("inputs/brief.pdf", "in/report.pdf")));
        assertThat(f.company.workshop().directorFiles(m)).containsExactly("inputs/brief.pdf", "in/report.pdf");

        f.record(f.company
                .workshop()
                .recordProjectMerged(f.director(), m, CHRIS, "def", false, "conflict in README.md"));
        assertThat(f.company.workshop().workspace(m, CHRIS).orElseThrow().baseCommit())
                .isEqualTo("abc");

        f.record(f.company.workshop().recordProjectMerged(f.director(), m, CHRIS, "def", true, null));
        assertThat(f.company.workshop().workspace(m, CHRIS).orElseThrow().baseCommit())
                .isEqualTo("def");
        assertThat(Company.replay(f.journal)
                        .workshop()
                        .workspace(m, CHRIS)
                        .orElseThrow()
                        .baseCommit())
                .isEqualTo("def");
    }

    @Test
    void neverUnderARunningAgentsFeetAndOnlyTheDirector() {
        MissionId m = mission();
        TicketId t = ticket;
        assertThatThrownBy(() -> f.company.workshop().recordDirectorFiles(f.agent(CHRIS), m, "def", List.of("a.txt")))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");

        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "claude-sonnet-5-5", null));
        assertThatThrownBy(() -> f.company.workshop().recordProjectMerged(f.director(), m, CHRIS, "def", true, null))
                .extracting("code")
                .isEqualTo("AGENT_AT_WORK");
    }
}
