package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every basis Floor42 writes for a decision is read back as its code and values; any other text has none. */
class DecisionBasisTest {

    private static Phrase read(String text) {
        return DecisionBasis.read(text).orElseThrow(() -> new AssertionError("no code for " + text));
    }

    @Test
    void theSentencesFloor42WritesAreReadBack() {
        QuestionId q = new QuestionId("Q-2");
        assertThat(read(DecisionBasis.directorAllowed(q))).isEqualTo(Phrase.of("directorAllowed", "question", "Q-2"));
        assertThat(read(DecisionBasis.directorDenied(q))).isEqualTo(Phrase.of("directorDenied", "question", "Q-2"));
        assertThat(read("director already allowed this exact command from chris (Q-2)"))
                .isEqualTo(Phrase.of("alreadyAllowedCommand", "question", "Q-2", "agent", "chris"));
        assertThat(read("director already denied this exact command from chris (Q-2)"))
                .isEqualTo(Phrase.of("alreadyDeniedCommand", "question", "Q-2", "agent", "chris"));
        assertThat(read("director already allowed every Write request from chris (Q-1)"))
                .isEqualTo(Phrase.of("alreadyAllowedTool", "question", "Q-1", "tool", "Write", "agent", "chris"));
        assertThat(read("director already denied every Write request from chris (Q-1)"))
                .isEqualTo(Phrase.of("alreadyDeniedTool", "question", "Q-1", "tool", "Write", "agent", "chris"));
        assertThat(read(DecisionBasis.floor42Tools())).isEqualTo(Phrase.of("floor42Tools"));
    }

    @Test
    void theSentencesOfJournalsBefore1_10AreStillReadBack() {
        assertThat(read("director answered Q-2")).isEqualTo(Phrase.of("directorAnswered", "question", "Q-2"));
        assertThat(read("director already answered Q-2 (this exact command from chris)"))
                .isEqualTo(Phrase.of("alreadyAnsweredCommand", "question", "Q-2", "agent", "chris"));
        assertThat(read("director already answered Q-1 (every Write request from chris)"))
                .isEqualTo(Phrase.of("alreadyAnsweredTool", "question", "Q-1", "tool", "Write", "agent", "chris"));
        assertThat(read(DecisionBasis.floor42Tools())).isEqualTo(Phrase.of("floor42Tools"));
        assertThat(read(DecisionBasis.defaultDeny())).isEqualTo(Phrase.of("defaultDeny"));
        assertThat(read(DecisionBasis.missionCancelled())).isEqualTo(Phrase.of("missionCancelled"));
        assertThat(read(DecisionBasis.missionNotActive(MissionStatus.COMPLETED)))
                .isEqualTo(Phrase.of("missionNotActive", "status", "COMPLETED"));
        assertThat(read(DecisionBasis.notOnTicket(new AgentId("chris"), new TicketId("T-4"))))
                .isEqualTo(Phrase.of("notOnTicket", "agent", "chris", "ticket", "T-4"));
    }

    @Test
    void aRuleIsReadWithItsDecisionTargetAgentAndTicket() {
        PermissionRule any = new PermissionRule(null, ActionCategory.EXECUTE, "Bash", "git", Decision.ALLOW, null);
        assertThat(read(DecisionBasis.ofRule(any)))
                .isEqualTo(new Phrase(
                        "rule", Map.of("decision", "ALLOW", "target", "Bash 'git'", "agent", "", "ticket", "")));
        PermissionRule mine = new PermissionRule(
                new AgentId("chris"), ActionCategory.WRITE, "Write", null, Decision.DENY, new TicketId("T-1"));
        assertThat(read(DecisionBasis.ofRule(mine)))
                .isEqualTo(new Phrase(
                        "ruleAgentTicket",
                        Map.of("decision", "DENY", "target", "Write", "agent", "chris", "ticket", "T-1")));
    }

    @Test
    void anyOtherTextHasNoCode() {
        assertThat(DecisionBasis.read(null)).isEmpty();
        assertThat(DecisionBasis.read("src/x is outside the agent's worktree")).isEmpty();
        assertThat(DecisionBasis.read("director answered yes")).isEmpty();
        assertThat(DecisionBasis.read("director allowed it (yes)")).isEmpty();
        assertThat(DecisionBasis.read("director already denied something (Q-1)"))
                .isEmpty();
        assertThat(DecisionBasis.read("mission is gone")).isEmpty();
    }
}
