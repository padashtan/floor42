package dev.floor42.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The precedence of permission rules (D15, ADR-0046), every combination: a rule names, or not, an agent, a tool, a
 * command and a ticket (a ticket's rule always names its tool). When two rules match a call, the one naming more wins,
 * compared in that order (agent, then tool, then command, then ticket); between rules naming the same things, the
 * more restrictive.
 */
class PermissionPrecedenceTest {

    private static final AgentId AGENT = new AgentId("chris");
    private static final TicketId TICKET = new TicketId("T-1");

    /** What a rule names: the four things it may narrow. */
    private record Shape(boolean agent, boolean tool, boolean command, boolean ticket) {
        PermissionRule rule(Decision d) {
            return new PermissionRule(
                    agent ? AGENT : null,
                    ActionCategory.EXECUTE,
                    tool ? "Bash" : null,
                    command ? "git" : null,
                    d,
                    ticket ? TICKET : null);
        }

        /** Agent, tool, command, ticket: the first that differs decides. */
        int compare(Shape o) {
            if (agent != o.agent) return agent ? 1 : -1;
            if (tool != o.tool) return tool ? 1 : -1;
            if (command != o.command) return command ? 1 : -1;
            if (ticket != o.ticket) return ticket ? 1 : -1;
            return 0;
        }
    }

    @Test
    void theRuleNamingMoreWinsInTheOrderAgentToolCommandTicketThenTheMoreRestrictive() {
        List<Shape> shapes = shapes();
        assertThat(shapes).hasSize(12);
        int pairs = 0;
        for (Shape a : shapes) {
            for (Shape b : shapes) {
                for (Decision da : Decision.values()) {
                    for (Decision db : Decision.values()) {
                        PermissionPolicy policy = policyOf(a.rule(da), b.rule(db));
                        Decision expected =
                                a.compare(b) > 0 ? da : a.compare(b) < 0 ? db : da.ordinal() >= db.ordinal() ? da : db;
                        Decision got = policy.evaluate(AGENT, TICKET, ActionCategory.EXECUTE, "Bash", "git status")
                                .decision();
                        assertThat(got)
                                .as(a + " " + da + " against " + b + " " + db)
                                .isEqualTo(expected);
                        pairs++;
                    }
                }
            }
        }
        assertThat(pairs).isEqualTo(12 * 12 * Decision.values().length * Decision.values().length);
    }

    /** The two cases the design review asked about, spelled out. */
    @Test
    void aTicketsRuleOutranksOnlyTheMissionsLineForItsTool() {
        PermissionPolicy policy = policyOf(
                PermissionRule.forTool("Bash", ActionCategory.EXECUTE, Decision.DENY),
                new PermissionRule(null, ActionCategory.EXECUTE, "Bash", "rm", Decision.DENY),
                PermissionRule.forTicket(TICKET, "Bash", ActionCategory.EXECUTE, Decision.ASK));

        assertThat(policy.evaluate(AGENT, TICKET, ActionCategory.EXECUTE, "Bash", "ls")
                        .decision())
                .as("over the mission's line for Bash")
                .isEqualTo(Decision.ASK);
        assertThat(policy.evaluate(AGENT, TICKET, ActionCategory.EXECUTE, "Bash", "rm -rf x")
                        .decision())
                .as("under the mission's rule naming the command")
                .isEqualTo(Decision.DENY);
    }

    /** A reviewer reruns tests (P1a): Bash opens for it on one ticket, and no writing tool opens anywhere. */
    @Test
    void aReviewersBashLineOpensBashOnItsTicketAndNothingElse() {
        PermissionPolicy policy = policyOf(
                new PermissionRule(AGENT, ActionCategory.WRITE, null, Decision.DENY),
                new PermissionRule(AGENT, ActionCategory.EXECUTE, null, Decision.DENY),
                new PermissionRule(AGENT, ActionCategory.NETWORK, null, Decision.DENY),
                new PermissionRule(AGENT, ActionCategory.EXECUTE, "Bash", null, Decision.ALLOW, TICKET));

        assertThat(policy.evaluate(AGENT, TICKET, ActionCategory.EXECUTE, "Bash", "mvn test")
                        .decision())
                .isEqualTo(Decision.ALLOW);
        for (String writing : List.of("Write", "Edit", "NotebookEdit")) {
            assertThat(policy.evaluate(AGENT, TICKET, ActionCategory.WRITE, writing, "x")
                            .decision())
                    .as(writing)
                    .isEqualTo(Decision.DENY);
        }
        assertThat(policy.evaluate(AGENT, TICKET, ActionCategory.EXECUTE, "PowerShell", "dir")
                        .decision())
                .as("another shell")
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(AGENT, TICKET, ActionCategory.NETWORK, "WebFetch", "u")
                        .decision())
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(AGENT, new TicketId("T-2"), ActionCategory.EXECUTE, "Bash", "ls")
                        .decision())
                .as("another ticket")
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(AGENT, null, ActionCategory.EXECUTE, "Bash", "ls")
                        .decision())
                .as("outside any ticket")
                .isEqualTo(Decision.DENY);
        assertThat(policy.evaluate(new AgentId("someone"), TICKET, ActionCategory.EXECUTE, "Bash", "ls")
                        .decision())
                .as("another agent: nothing allows it")
                .isEqualTo(Decision.DENY);
    }

    @Test
    void aTicketsRuleNamesItsTool() {
        assertThatThrownBy(() -> PermissionRule.forTicket(TICKET, null, ActionCategory.WRITE, Decision.ASK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names its tool");
        assertThatThrownBy(() -> new PermissionRule(null, ActionCategory.WRITE, null, null, Decision.ASK, TICKET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A mission holding these rules, recorded the way the director records them: only the mission book changes a
     * policy (DomainOwnershipTest). Its first ticket is {@link #TICKET}.
     */
    private static PermissionPolicy policyOf(PermissionRule... rules) {
        Company c = new Company();
        Instant at = Instant.parse("2026-11-01T09:00:00Z");
        CommandContext director = new CommandContext(Actor.director(), Origin.SIMULATED, at, null);
        c.staff().hire(director, AGENT, "Chris", "developer", WorkRole.DEVELOPER, "claude-sonnet-5-5", "medium");
        var created = c.missions().create(director, "Precedence", "Rules against rules");
        MissionId m = ((dev.floor42.domain.event.MissionEvent.MissionCreated)
                        created.getFirst().event())
                .mission();
        c.tickets().create(director, m, "The ticket", "", Priority.NORMAL, List.of(), List.of("done"));
        for (PermissionRule r : rules) c.missions().addPermission(director, m, r);
        return c.missions().find(m).orElseThrow().policy();
    }

    private static List<Shape> shapes() {
        List<Shape> all = new ArrayList<>();
        for (int bits = 0; bits < 16; bits++) {
            Shape s = new Shape((bits & 8) != 0, (bits & 4) != 0, (bits & 2) != 0, (bits & 1) != 0);
            if (!s.ticket() || s.tool()) all.add(s);
        }
        return all;
    }
}
