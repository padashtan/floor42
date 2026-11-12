package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director chooses, tool by tool, between full autonomy, asking once, asking every time, and forbidding. */
@ExtendWith(GoldenFixtureExtension.class)
class ToolPermissionTableTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    {
        // Category default for writes: ask once.
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK)));
    }

    List<EventEnvelope> set(String tool, ActionCategory category, Decision decision) {
        return f.record(f.company.missions().setToolPermission(f.director(), m, tool, category, decision));
    }

    void request(String id, String tool, String summary) {
        f.record(f.company
                .actions()
                .request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, ActionCategory.WRITE, tool, summary));
    }

    ActionStage stage(String id) {
        return f.company.actions().find(new ActionId(id)).orElseThrow().stage();
    }

    @Test
    void autoLetsTheToolRunWithoutAskingWhileOtherToolsKeepTheirSetting() {
        set("Write", ActionCategory.WRITE, Decision.ALLOW);

        request("a1", "Write", "notes.txt");
        request("a2", "Edit", "pom.xml");

        assertThat(stage("a1")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
    }

    @Test
    void askEveryTimeOpensOneQuestionPerCallAndEachAnswerSettlesOnlyItsCall() {
        set("Write", ActionCategory.WRITE, Decision.ASK_ALWAYS);
        request("a1", "Write", "notes.txt");
        request("a2", "Write", "summary.txt");
        assertThat(f.company.desk().open()).hasSize(2);

        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "ALLOW"));

        assertThat(stage("a1")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open().getFirst().context()).contains("this call only");
    }

    @Test
    void askEveryTimeIgnoresPreviousAnswers() {
        request("a1", "Write", "notes.txt");
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), "ALLOW"));
        set("Write", ActionCategory.WRITE, Decision.ASK_ALWAYS);

        request("a2", "Write", "summary.txt");

        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open()).hasSize(1);
    }

    @Test
    void forbiddenToolsAreDeniedWithoutAQuestion() {
        set("Write", ActionCategory.WRITE, Decision.DENY);

        request("a1", "Write", "notes.txt");

        assertThat(stage("a1")).isEqualTo(ActionStage.DENIED);
        assertThat(f.company.desk().open()).isEmpty();
    }

    @Test
    void aNewSettingReplacesThePreviousOneAtomically() {
        set("Write", ActionCategory.WRITE, Decision.ALLOW);

        List<EventEnvelope> events = set("Write", ActionCategory.WRITE, Decision.DENY);

        assertThat(events)
                .extracting(EventEnvelope::type)
                .containsExactly("PermissionRuleRemoved", "PermissionRuleAdded");
        assertThat(f.company.missions().find(m).orElseThrow().policy().rules())
                .filteredOn(PermissionRule::coversWholeTool)
                .containsExactly(PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.DENY));
    }

    @Test
    void choosingTheCurrentSettingAgainRecordsNothing() {
        set("Write", ActionCategory.WRITE, Decision.ALLOW);

        assertThat(set("Write", ActionCategory.WRITE, Decision.ALLOW)).isEmpty();
    }

    @Test
    void anAgentSpecificRuleStaysStrongerThanTheTable() {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(CHRIS, ActionCategory.WRITE, null, Decision.DENY)));
        set("Write", ActionCategory.WRITE, Decision.ALLOW);

        request("a1", "Write", "notes.txt");

        assertThat(stage("a1")).isEqualTo(ActionStage.DENIED);
    }

    @Test
    void onlyTheDirectorChoosesTheSettings() {
        assertThatThrownBy(() -> f.company
                        .missions()
                        .setToolPermission(f.agent(CHRIS), m, "Write", ActionCategory.WRITE, Decision.ALLOW))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
    }
}
