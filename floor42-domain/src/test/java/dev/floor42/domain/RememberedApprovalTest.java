package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.GEOFFREY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The director is asked once per tool and agent (once per exact command for the shell), never again on the mission. */
@ExtendWith(GoldenFixtureExtension.class)
class RememberedApprovalTest {

    final Fixture f = new Fixture();
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    {
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.WRITE, null, Decision.ASK)));
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(null, ActionCategory.EXECUTE, null, Decision.ASK)));
    }

    void request(String id, AgentId agent, ActionCategory category, String tool, String summary) {
        f.record(f.company
                .actions()
                .request(
                        f.agent(agent),
                        new ActionId(id),
                        m,
                        agent.equals(CHRIS) ? t : null,
                        agent,
                        category,
                        tool,
                        summary));
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
    void aSecondRequestForTheSameToolIsDecidedWithoutAskingAgain() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");

        request("a2", CHRIS, ActionCategory.WRITE, "Write", "report.md");

        assertThat(stage("a2")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(f.company.actions().find(new ActionId("a2")).orElseThrow().decisionBasis())
                .isEqualTo("director already allowed every Write request from chris (Q-1)");
        assertThat(f.company.desk().of(m)).hasSize(1);
        assertThat(f.company.queries().statusOf(CHRIS)).isNotEqualTo(AgentStatus.WAITING_FOR_DIRECTOR);
    }

    @Test
    void aRefusalIsRememberedToo() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("DENY");

        request("a2", CHRIS, ActionCategory.WRITE, "Write", "other.txt");

        assertThat(stage("a2")).isEqualTo(ActionStage.DENIED);
        assertThat(f.company.desk().open()).isEmpty();
    }

    @Test
    void anotherToolIsAskedSeparately() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");

        request("a2", CHRIS, ActionCategory.WRITE, "Edit", "pom.xml");

        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::action)
                .isEqualTo(new ActionId("a2"));
    }

    @Test
    void approvingOneShellCommandDoesNotApproveTheShell() {
        request("a1", CHRIS, ActionCategory.EXECUTE, "Bash", "docker restart gateway");
        answerOpen("ALLOW");

        request("a2", CHRIS, ActionCategory.EXECUTE, "Bash", "docker restart gateway");
        request("a3", CHRIS, ActionCategory.EXECUTE, "Bash", "docker rm -f gateway");

        assertThat(stage("a2")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a3")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open().getFirst().context()).contains("this exact command");
    }

    @Test
    void aCommandCutAtTheSummaryCapIsNeverCoveredByAnEarlierApproval() {
        String head = "echo " + "x".repeat(294) + "…";
        request("a1", CHRIS, ActionCategory.EXECUTE, "Bash", head);
        answerOpen("ALLOW");
        assertThat(stage("a1")).isEqualTo(ActionStage.AUTHORIZED);

        // Same first 299 characters, a different tail the summary no longer shows.
        request("a2", CHRIS, ActionCategory.EXECUTE, "Bash", head);

        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::action)
                .isEqualTo(new ActionId("a2"));
    }

    @Test
    void anApprovalBelongsToTheAgentItWasGivenTo() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");

        request("a2", GEOFFREY, ActionCategory.WRITE, "Write", "plan.md");

        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::askedBy)
                .isEqualTo(GEOFFREY);
    }

    @Test
    void requestsArrivingWhileTheQuestionIsOpenShareItsAnswer() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        request("a2", CHRIS, ActionCategory.WRITE, "Write", "notes2.txt");

        assertThat(f.company.desk().open()).hasSize(1);
        answerOpen("ALLOW");

        assertThat(stage("a1")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a2")).isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void anExplicitRuleOverridesARememberedAnswer() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(CHRIS, ActionCategory.WRITE, null, Decision.DENY)));

        request("a2", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");

        assertThat(stage("a2")).isEqualTo(ActionStage.DENIED);
        assertThat(f.company.actions().find(new ActionId("a2")).orElseThrow().denialReason())
                .isEqualTo(DenialReason.POLICY);
    }

    @Test
    void theQuestionTellsTheDirectorWhatTheAnswerWillCover() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");

        assertThat(f.company.desk().open().getFirst().context())
                .contains("notes.txt")
                .contains("every Write request from chris");
    }

    @Test
    void rememberedDecisionsSurviveAReplayOfTheJournal() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");

        Company rebuilt = Company.replay(f.journal);
        rebuilt.actions()
                .request(f.agent(CHRIS), new ActionId("a2"), m, t, CHRIS, ActionCategory.WRITE, "Write", "b.txt");

        assertThat(rebuilt.actions().find(new ActionId("a2")).orElseThrow().stage())
                .isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void theDirectorCanForgetARememberedAnswer() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");
        QuestionId q1 = new QuestionId("Q-1");
        assertThat(f.company.desk().remembered(q1)).isTrue();

        f.record(f.company.desk().forgetApproval(f.director(), q1));
        request("a2", CHRIS, ActionCategory.WRITE, "Write", "report.md");

        assertThat(f.company.desk().remembered(q1)).isFalse();
        assertThat(stage("a1")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::action)
                .isEqualTo(new ActionId("a2"));
        assertThat(Company.replay(f.journal).desk().remembered(q1)).isFalse();
    }

    @Test
    void onlyTheDirectorForgetsAndOnlyARememberedAnswer() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        QuestionId q1 = new QuestionId("Q-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> f.company.desk().forgetApproval(f.director(), q1))
                .hasMessageContaining("not a remembered approval");
        answerOpen("ALLOW");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> f.company.desk().forgetApproval(f.agent(CHRIS), q1))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void anAnswerForOneCallOnlyIsNeverReused() {
        f.record(f.company
                .missions()
                .setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.ASK_ALWAYS));
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("ALLOW");
        f.record(f.company.missions().setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.ASK));

        request("a2", CHRIS, ActionCategory.WRITE, "Write", "report.md");

        assertThat(f.company.desk().remembered(new QuestionId("Q-1"))).isFalse();
        assertThat(stage("a2")).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::action)
                .isEqualTo(new ActionId("a2"));
    }

    void agentAsks(String subject, String... options) {
        f.record(f.company
                .desk()
                .ask(f.agent(CHRIS), m, subject, "", List.of(options), null, Criticality.MEDIUM, true, t));
    }

    @Test
    void theDecisionSaysPlainlyWhetherTheDirectorAllowedOrDenied() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("DENY");
        request("a2", CHRIS, ActionCategory.WRITE, "Write", "other.txt");

        assertThat(f.company.actions().find(new ActionId("a1")).orElseThrow().decisionBasis())
                .isEqualTo("director denied it (Q-1)");
        assertThat(f.company.actions().find(new ActionId("a2")).orElseThrow().decisionBasis())
                .isEqualTo("director already denied every Write request from chris (Q-1)");
    }

    void answerOpenAskingAgain(String answer, String denial) {
        f.record(f.company
                .desk()
                .answer(f.director(), f.company.desk().open().getFirst().id(), answer, new QuestionId(denial)));
    }

    @Test
    void askingAgainForOneRefusedCallAsksTheDirectorAgainThenFollowsTheNewAnswer() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("DENY");
        agentAsks("My write of notes.txt was denied. What now?", "Retry the write", "Give up");
        assertThat(f.company.desk().liftable(new QuestionId("Q-2")))
                .extracting(Question::id)
                .containsExactly(new QuestionId("Q-1"));
        answerOpenAskingAgain("Retry the write", "Q-1");

        assertThat(f.company.desk().remembered(new QuestionId("Q-1")))
                .as("the director's choice lifts the remembered refusal")
                .isFalse();
        request("a2", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        assertThat(stage("a2")).as("asked again, never allowed by the lift").isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::action)
                .isEqualTo(new ActionId("a2"));

        answerOpen("ALLOW");
        assertThat(stage("a2")).isEqualTo(ActionStage.AUTHORIZED);
        assertThat(stage("a1")).as("the refused call keeps its decision").isEqualTo(ActionStage.DENIED);
        assertThat(Company.replay(f.journal).desk().remembered(new QuestionId("Q-1")))
                .isFalse();
    }

    @Test
    void freeTextNeverLiftsARefusalWhateverItSays() {
        request("a1", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("DENY");
        for (String answer : List.of(
                "Retry the write", "no retry", "ne r\u00e9essaie pas", "ne plus r\u00e9essayer", "Retry, but not X")) {
            agentAsks("My write was denied. What now?");
            answerOpen(answer);
        }
        request("a2", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");

        assertThat(stage("a2")).isEqualTo(ActionStage.DENIED);
        assertThat(f.company.desk().remembered(new QuestionId("Q-1"))).isTrue();
    }

    @Test
    void askingAgainLiftsExactlyTheRefusalNamedAndNoOther() {
        request("a1", CHRIS, ActionCategory.EXECUTE, "Bash", "rm -rf build");
        answerOpen("DENY");
        request("a2", CHRIS, ActionCategory.WRITE, "Write", "notes.txt");
        answerOpen("DENY");
        agentAsks("Two calls were denied. What now?", "Retry", "Give up");
        answerOpenAskingAgain("Retry", "Q-1");

        assertThat(f.company.desk().remembered(new QuestionId("Q-1"))).isFalse();
        assertThat(f.company.desk().remembered(new QuestionId("Q-2")))
                .as("the write refusal still stands")
                .isTrue();
    }

    @Test
    void onlyTheAskingAgentsEarlierRefusalCanBeLiftedAndNothingIsRecordedOtherwise() {
        request("a1", GEOFFREY, ActionCategory.WRITE, "Write", "plan.md");
        answerOpen("DENY");
        agentAsks("What now?", "Retry", "Give up");
        int before = f.journal.size();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> answerOpenAskingAgain("Retry", "Q-1"))
                .isInstanceOf(DomainException.class);
        assertThat(f.journal).hasSize(before);
        assertThat(f.company.desk().remembered(new QuestionId("Q-1"))).isTrue();
        assertThat(f.company.desk().liftable(new QuestionId("Q-2"))).isEmpty();
    }
}
