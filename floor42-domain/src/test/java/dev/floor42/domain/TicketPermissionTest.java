package dev.floor42.domain;

import static dev.floor42.domain.Fixture.CHRIS;
import static dev.floor42.domain.Fixture.DARIUS;
import static dev.floor42.domain.Fixture.GALEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * D15: a permission may cover the work of one ticket only. A decision mission keeps its read-only table; its
 * implementation ticket alone may write, after asking the director, and its reviewer still may not.
 */
@ExtendWith(GoldenFixtureExtension.class)
class TicketPermissionTest {

    private final Fixture f = new Fixture();

    @Test
    void aTicketsRuleOpensItsToolOnThatTicketOnly() {
        MissionId m = f.mission();
        TicketId decision = f.ticket(m, "Decide: the gateway");
        TicketId implementation = f.ticket(m, "Implement: the gateway");
        f.record(f.company.missions().setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.DENY));
        f.record(f.company
                .missions()
                .addPermission(
                        f.director(),
                        m,
                        PermissionRule.forTicket(implementation, "Write", ActionCategory.WRITE, Decision.ASK)));
        f.record(f.company
                .missions()
                .addPermission(f.director(), m, new PermissionRule(DARIUS, ActionCategory.WRITE, null, Decision.DENY)));
        PermissionPolicy policy = f.company.missions().find(m).orElseThrow().policy();

        assertThat(policy.evaluate(CHRIS, implementation, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .isEqualTo(Decision.ASK);
        assertThat(policy.evaluate(CHRIS, decision, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .as("another ticket of the mission")
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(CHRIS, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .as("outside any ticket")
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(DARIUS, implementation, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .as("a rule naming the agent outranks the ticket's")
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(CHRIS, implementation, ActionCategory.WRITE, "Edit", "a.txt")
                        .decision())
                .as("the rule names one tool")
                .isEqualTo(Decision.DENY);
    }

    @Test
    void aTicketsRuleIsNotTheDirectorsTableLineAndReadsAsSuch() {
        TicketId t = new TicketId("T-7");
        PermissionRule rule = PermissionRule.forTicket(t, "Write", ActionCategory.WRITE, Decision.ASK);

        assertThat(rule.coversWholeTool()).isFalse();
        assertThat(rule).hasToString("ASK Write for any agent on T-7");
        assertThat(PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.ASK))
                .hasToString("ASK Write for any agent");
        assertThat(rule.matches(CHRIS, ActionCategory.WRITE, "Write", null))
                .as("a mission-wide question never meets a ticket's rule")
                .isFalse();
    }

    @Test
    void aTicketsRuleNamesATicketOfTheMission() {
        MissionId m = f.mission();
        MissionId other = f.mission();
        TicketId elsewhere = f.ticket(other, "Elsewhere");

        assertThatThrownBy(() -> f.company
                        .missions()
                        .addPermission(
                                f.director(),
                                m,
                                PermissionRule.forTicket(elsewhere, "Write", ActionCategory.WRITE, Decision.ASK)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("is not part of " + m);
    }

    /**
     * Seen live (Q-73, Q-74): after a committee's adoption the implementation ticket had Floor42's ASK Write; the
     * director set Write to ALLOW for the mission and was still asked 12 s later. The director's line now replaces the
     * tickets' lines of that tool, and leaves the rules naming an agent.
     */
    @Test
    void theDirectorsLineForAToolReplacesTheTicketsLinesForThatTool() {
        MissionId m = f.runningMission();
        TicketId implementation = f.firstTicket(m);
        f.record(f.company.missions().setToolPermission(f.system(), m, "Write", ActionCategory.WRITE, Decision.DENY));
        f.record(f.company.missions().setToolPermission(f.system(), m, "Edit", ActionCategory.WRITE, Decision.DENY));
        for (String tool : List.of("Write", "Edit")) {
            f.record(f.company
                    .missions()
                    .addPermission(
                            f.system(),
                            m,
                            PermissionRule.forTicket(implementation, tool, ActionCategory.WRITE, Decision.ASK)));
        }
        PermissionRule reviewer = new PermissionRule(DARIUS, ActionCategory.WRITE, null, Decision.DENY);
        f.record(f.company.missions().addPermission(f.system(), m, reviewer));

        f.record(
                f.company.missions().setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.ALLOW));
        f.record(f.company
                .actions()
                .request(
                        f.agent(CHRIS),
                        new ActionId("w1"),
                        m,
                        implementation,
                        CHRIS,
                        ActionCategory.WRITE,
                        "Write",
                        "gateway.md"));

        assertThat(f.company.actions().find(new ActionId("w1")).orElseThrow().stage())
                .as("allowed without a question")
                .isEqualTo(ActionStage.AUTHORIZED);
        assertThat(f.company.desk().open()).isEmpty();
        PermissionPolicy policy = f.company.missions().find(m).orElseThrow().policy();
        assertThat(policy.rules())
                .as("Edit's ticket line and the reviewer's denial stay")
                .contains(
                        PermissionRule.forTicket(implementation, "Edit", ActionCategory.WRITE, Decision.ASK), reviewer)
                .doesNotContain(PermissionRule.forTicket(implementation, "Write", ActionCategory.WRITE, Decision.ASK));
        assertThat(policy.evaluate(DARIUS, implementation, ActionCategory.WRITE, "Write", "a.txt")
                        .decision())
                .isEqualTo(Decision.DENY);
    }

    @Test
    void floor42sOwnLineForAToolLeavesTheTicketsLines() {
        MissionId m = f.mission();
        TicketId implementation = f.ticket(m, "Implement: the gateway");
        PermissionRule ticketLine =
                PermissionRule.forTicket(implementation, "Write", ActionCategory.WRITE, Decision.ASK);
        f.record(f.company.missions().addPermission(f.system(), m, ticketLine));

        f.record(f.company.missions().setToolPermission(f.system(), m, "Write", ActionCategory.WRITE, Decision.DENY));

        assertThat(f.company.missions().find(m).orElseThrow().policy().rules()).contains(ticketLine);
    }

    @Test
    void theTableKeepsWhoRecordedEachRule() {
        MissionId m = f.runningMission();
        f.record(f.company.missions().setToolPermission(f.system(), m, "Bash", ActionCategory.EXECUTE, Decision.ASK));
        f.record(
                f.company.missions().setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.ALLOW));
        PermissionPolicy policy = f.company.missions().find(m).orElseThrow().policy();

        assertThat(policy.author(PermissionRule.forTool("Bash", ActionCategory.EXECUTE, Decision.ASK)))
                .contains(Actor.system());
        assertThat(policy.author(PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.ALLOW)))
                .contains(Actor.director());
        assertThat(policy.author(PermissionRule.forTool("Edit", ActionCategory.WRITE, Decision.ALLOW)))
                .as("a rule not in force")
                .isEmpty();
        assertThat(Company.replay(f.journal)
                        .missions()
                        .find(m)
                        .orElseThrow()
                        .policy()
                        .author(PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.ALLOW)))
                .as("read back from the journal's actor")
                .contains(Actor.director());
        assertThatThrownBy(() -> f.company
                        .missions()
                        .setToolPermission(f.agent(CHRIS), m, "Write", ActionCategory.WRITE, Decision.ALLOW))
                .extracting("code")
                .isEqualTo("DIRECTOR_ONLY");
    }

    /**
     * Before 1.14.0 Floor42 journaled a mission's starting table as the director's. Lines recorded before the first plan
     * are Floor42's: a committee's read-only table does not pass for the director's choice, and the implementation gets
     * the defaults; a line the director set afterwards stays the director's choice, against the defaults.
     */
    @Test
    void theStartingTableIsFloor42sAndOnlyTheDirectorsLaterLinesBeatTheDefaults() {
        MissionId m = f.mission();
        f.ticket(m, "Decide: the gateway");
        f.record(f.company.missions().setToolPermission(f.director(), m, "Write", ActionCategory.WRITE, Decision.DENY));
        f.record(f.company.missions().setToolPermission(f.director(), m, "Edit", ActionCategory.WRITE, Decision.DENY));
        f.record(f.company.missions().submitPlan(f.agent(GALEN), m));
        f.record(f.company.missions().setToolPermission(f.director(), m, "Edit", ActionCategory.WRITE, Decision.ALLOW));
        Mission mission = f.company.missions().find(m).orElseThrow();

        assertThat(mission.policy().author(PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.DENY)))
                .contains(Actor.system());
        assertThat(DefaultPermissions.forImplementation(mission, "Write", ActionCategory.WRITE, Decision.ASK))
                .as("the committee's line gives way to the default")
                .isEqualTo(Decision.ASK);
        assertThat(DefaultPermissions.forImplementation(mission, "Edit", ActionCategory.WRITE, Decision.ASK))
                .as("the director's own line stays")
                .isEqualTo(Decision.ALLOW);
    }
}
