package dev.floor42.server.api.mission;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.server.app.boot.MissionRecovery;
import dev.floor42.server.app.workspace.Workspaces;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a restart records beyond the interrupted session (ADR-0076): a call authorized and never reported, an approval
 * whose call died with the daemon, a worktree with changes no ticket explains. Once each, and a second restart adds
 * nothing.
 */
@QuarkusTest
class ReconciliationTest extends DispatchFixture {

    @Inject
    MissionRecovery recovery;

    @Inject
    Workspaces workspaces;

    private AgentSession running(MissionId m) throws InterruptedException {
        approve(m);
        await("a session runs", Duration.ofSeconds(30), () -> {
            answerAll(m, null);
            return office.query(c -> c.sessions().of(m).stream().anyMatch(AgentSession::isOpen));
        });
        return office.query(c -> c.sessions().of(m).getFirst());
    }

    private void request(MissionId m, AgentSession s, String id, ActionCategory category, String tool) {
        office.execute(c -> c.actions()
                .request(
                        office.context(Actor.system(), Origin.REAL),
                        new ActionId(id),
                        m,
                        s.ticket(),
                        s.agent(),
                        category,
                        tool,
                        tool + " on a file"));
    }

    private ActionStage stageOfRead() {
        return office.query(c ->
                c.actions().find(new ActionId("A-recovery-read")).orElseThrow().stage());
    }

    private int deskSize(MissionId m) {
        return office.query(c -> c.desk().of(m).size());
    }

    private List<String> recoveryRows(MissionId m, String code) {
        return office.query(c -> c.desk().of(m).stream()
                .map(Question::phrase)
                .filter(p -> p != null && code.equals(p.code()))
                .map(p -> p.params().get("key"))
                .toList());
    }

    @Test
    void anAuthorizedCallWithoutOutcomeAndADeadApprovalAreRecordedOnceAcrossRestarts() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        AgentSession s = running(m);
        request(m, s, "A-recovery-read", ActionCategory.READ, "Read");
        request(m, s, "A-recovery-write", ActionCategory.WRITE, "Write");
        assertThat(stageOfRead()).isEqualTo(ActionStage.AUTHORIZED);

        recovery.recover();

        assertThat(recoveryRows(m, "recoveryOutcomeUnknown")).containsExactly("A-recovery-read");
        assertThat(recoveryRows(m, "recoveryApprovalOrphaned")).hasSize(1);
        // Nothing is invented: the call still has no outcome in the journal.
        assertThat(stageOfRead()).isEqualTo(ActionStage.AUTHORIZED);
        int before = deskSize(m);

        recovery.recover();

        assertThat(deskSize(m)).as("a second restart adds nothing").isEqualTo(before);
        cancel(m);
    }

    @Test
    void aWorktreeWithChangesAndNoTicketInProgressIsRecordedOnce() throws Exception {
        MissionId m = mission("Write notes [fake:slow=4]");
        AgentSession s = running(m);
        Path ghost = workspaces.worktree(
                office.query(c -> c.missions().find(m).orElseThrow().workspace()), m, new AgentId("ghost"));
        Files.writeString(ghost.resolve("loose.txt"), "left behind");

        recovery.recover();

        assertThat(recoveryRows(m, "recoveryWorktreeUnclaimed")).containsExactly("ghost");
        // The agent whose ticket is in progress is not flagged.
        assertThat(recoveryRows(m, "recoveryWorktreeUnclaimed"))
                .doesNotContain(s.agent().value());
        recovery.recover();
        assertThat(recoveryRows(m, "recoveryWorktreeUnclaimed")).hasSize(1);
        assertThat(Files.exists(ghost.resolve("loose.txt")))
                .as("nothing is discarded")
                .isTrue();
        cancel(m);
    }
}
