package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Shadow mode (ADR-0082): the agent's write, run and network calls are denied and left to the director. */
class ShadowModeTest {

    final Fixture f = new Fixture(Origin.REAL);
    final MissionId m = f.runningMission();
    final TicketId t = f.firstTicket(m);

    Action call(String id, ActionCategory category, String tool, String summary) {
        f.record(f.company.actions().request(f.agent(CHRIS), new ActionId(id), m, t, CHRIS, category, tool, summary));
        return f.company.actions().find(new ActionId(id)).orElseThrow();
    }

    void shadow() {
        f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.SHADOW));
    }

    @Test
    void aShadowAgentsBashIsDeniedAndProposed() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        shadow();

        Action a = call("a1", ActionCategory.EXECUTE, "Bash", "mvn test");

        assertThat(a.stage()).isEqualTo(ActionStage.DENIED);
        assertThat(a.denialReason()).isEqualTo(DenialReason.POLICY);
        assertThat(a.decisionBasis()).isEqualTo(DecisionBasis.trustShadow());
        assertThat(DecisionBasis.read(a.decisionBasis())).contains(Phrase.of("trustShadow"));
        assertThat(f.company.desk().open()).isEmpty();
    }

    @Test
    void aShadowAgentsReadStillRuns() {
        f.allow(m, ActionCategory.READ, null, null);
        shadow();

        assertThat(call("a1", ActionCategory.READ, "Read", "README.md").stage()).isEqualTo(ActionStage.AUTHORIZED);
    }

    @Test
    void aTableDenyStaysADeny() {
        shadow();

        Action a = call("a1", ActionCategory.EXECUTE, "Bash", "mvn test");

        assertThat(a.stage()).isEqualTo(ActionStage.DENIED);
        assertThat(a.decisionBasis()).isEqualTo(DecisionBasis.defaultDeny());
    }

    @Test
    void aShadowAgentsWriteIsAProposalNotAQuestion() {
        f.allow(m, ActionCategory.WRITE, null, null);
        shadow();

        Action a = call("a1", ActionCategory.WRITE, "Write", "notes.txt");

        assertThat(a.decisionBasis()).isEqualTo(DecisionBasis.trustShadow());
        assertThat(f.company.desk().open()).isEmpty();
    }

    @Test
    void theAgentReadsNoRuleNameNorLevelHistory() {
        assertThat(DecisionBasis.SHADOW_AGENT_TEXT)
                .startsWith("Shadow mode: this call was not run.")
                .doesNotContain("rule", "PROBATION", "STANDARD");
    }

    @Test
    void probationAsksWhereTheTableAllows() {
        f.allow(m, ActionCategory.EXECUTE, null, null);
        f.record(f.company.staff().lowerTrust(f.director(), CHRIS, TrustLevel.PROBATION));

        Action a = call("a1", ActionCategory.EXECUTE, "Bash", "mvn test");

        assertThat(a.stage()).isEqualTo(ActionStage.REQUESTED);
        assertThat(f.company.desk().open())
                .singleElement()
                .extracting(Question::kind)
                .isEqualTo(QuestionKind.ACTION_APPROVAL);
    }
}
