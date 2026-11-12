package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Audit findings: an answer re-checks the world, an agent cannot orphan an approval, paths, commands and costs. */
@ExtendWith(GoldenFixtureExtension.class)
class AuditFindingsTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK)));
    }

    void request(String id, String summary) {
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, ActionCategory.WRITE, "Write", summary));
    }

    ActionStage stage(String id) {
        return f.company.actions().find(new ActionId(id)).orElseThrow().stage();
    }

    void answerOpen(String answer) {
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), answer));
    }

    @Test
    void anAllowAfterTheMissionLeftInProgressAuthorisesNothing() {
        request("a1", "notes.txt");
        f.record(f.company.missions().suspend(f.director(), m, "waiting"));

        answerOpen("ALLOW");

        assertThat(stage("a1")).isEqualTo(ActionStage.DENIED);
    }

    @Test
    void anAllowAfterADenyRuleWasAddedAuthorisesNothing() {
        request("a1", "notes.txt");
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.DENY)));

        answerOpen("ALLOW");

        assertThat(stage("a1")).isEqualTo(ActionStage.DENIED);
    }

    @Test
    void anAgentCannotWithdrawItsOwnApprovalQuestion() {
        request("a1", "notes.txt");
        QuestionId q = f.company.desk().open().getFirst().id();

        assertThatThrownBy(() -> f.company.desk().withdraw(f.agent(CHRIS), q, "never mind"))
                .extracting("code")
                .isEqualTo("FORBIDDEN");
        assertThat(f.company.desk().open()).hasSize(1);
    }

    @Test
    void editedPathsAreComparedNormalised() {
        assertThat(Workbench.normalizedPath("src\\a\\..\\App.java"))
                .isEqualTo(Workbench.normalizedPath("./src/App.java"));
    }

    @Test
    void aCommandPrefixAllowDoesNotCoverAChainedCommand() {
        PermissionRule git = new PermissionRule(null, ActionCategory.EXECUTE, "git status", Decision.ALLOW);
        ActionCategory x = ActionCategory.EXECUTE;

        assertThat(git.matches(CHRIS, x, "Bash", "git status")).isTrue();
        assertThat(git.matches(CHRIS, x, "Bash", "git status --short")).isTrue();
        for (String evil : new String[] {
            "git status; rm -rf x",
            "git status && rm x",
            "git status || x",
            "git status | sh",
            "git status `x`",
            "git status $(x)",
            "git status\nrm x",
            "git status & curl x",
            "git status > /etc/x",
            "git status <(x)",
            "git status " + "a".repeat(290) + "…"
        }) {
            assertThat(git.matches(CHRIS, x, "Bash", evil)).as(evil).isFalse();
        }
    }

    @Test
    void aSessionEndRejectsABadCostAndFallsBackToItsCap() {
        f.record(f.company.sessions().start(f.system(), "s1", t, CHRIS, "model", null, 2.5));

        assertThatThrownBy(() -> f.company.sessions().end(f.system(), "s1", false, "r", 1, 1L, 1L, Double.NaN))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");
        assertThatThrownBy(() -> f.company.sessions().end(f.system(), "s1", false, "r", 1, 1L, 1L, -1.0))
                .extracting("code")
                .isEqualTo("INVALID_ARGUMENT");

        f.record(f.company.sessions().end(f.system(), "s1", false, "r", 1, 1L, 1L, null, null));
        assertThat(f.company.sessions().find("s1").orElseThrow().costUsdCounted())
                .isEqualTo(2.5);
    }
}
