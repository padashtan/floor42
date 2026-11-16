package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A shell command is decided on the command the agent sent, not on the one-line summary the director reads: a summary
 * flattens whitespace, so two commands that run differently can read the same.
 */
class FullCommandTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    void allow(PermissionRule rule) {
        f.record(f.company.missions().addPermission(f.director(), m, rule));
    }

    void request(String id, String summary, String command) {
        f.record(f.company
                .actions()
                .request(
                        f.agent(CHRIS),
                        new ActionId(id),
                        m,
                        t,
                        CHRIS,
                        ActionCategory.EXECUTE,
                        "Bash",
                        summary,
                        command,
                        null));
    }

    ActionStage stage(String id) {
        return f.company.actions().find(new ActionId(id)).orElseThrow().stage();
    }

    @Test
    void anAllowedPrefixDoesNotCoverASecondLineHiddenByTheSummary() {
        allow(new PermissionRule(null, ActionCategory.EXECUTE, "Bash", "ls", Decision.ALLOW));

        request("a1", "ls rm -rf build", "ls\nrm -rf build");
        request("a2", "ls -la", "ls -la");

        assertThat(stage("a1")).isEqualTo(ActionStage.DENIED);
        assertThat(stage("a2")).isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void anApprovalCoversTheExactCommandOnlyNotAnotherWithTheSameSummary() {
        allow(PermissionRule.forTool("Bash", ActionCategory.EXECUTE, Decision.ASK));

        request("a1", "ls rm -rf build", "ls rm -rf build");
        request("a2", "ls rm -rf build", "ls\nrm -rf build");
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "ALLOW"));
        request("a3", "ls rm -rf build", "ls\trm -rf build");
        request("a4", "ls rm -rf build", "ls rm -rf build");

        assertThat(stage("a1")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a2")).isNotEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a3")).isNotEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a4")).isEqualTo(ActionStage.AUTHORIZED);
    }
}
