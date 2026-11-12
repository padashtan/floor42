package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Priority;
import dev.floor42.domain.Question;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.api.PermissionViews.RuleView;
import dev.floor42.server.api.PermissionViews.ToolSettingView;
import dev.floor42.server.api.QuestionViews.QuestionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the director reads about a mission's permissions: every rule that outranks the table's line, who recorded it,
 * and, on an approval question, the rule that made the call ask.
 */
class PermissionCauseTest {

    private static final AgentId CHRIS = new AgentId("chris");
    private static final AgentId DARIUS = new AgentId("darius");

    private final Company c = Company.replay(List.of());
    private Instant now = Instant.parse("2026-11-03T09:00:00Z");
    private final MissionId m;
    private final TicketId t;

    PermissionCauseTest() {
        for (AgentId id : List.of(CHRIS, DARIUS)) {
            c.staff()
                    .hire(
                            director(),
                            id,
                            id.value(),
                            "developer",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of());
        }
        m = ((MissionCreated) c.missions()
                        .create(director(), "Gateway", "Put a gateway in front of the APIs")
                        .getFirst()
                        .event())
                .mission();
        t = ((TicketCreated) c.tickets()
                        .create(floor42(), m, "Implement: the gateway", "", Priority.NORMAL, List.of(), List.of("done"))
                        .getFirst()
                        .event())
                .ticket();
        c.tickets().assign(floor42(), t, CHRIS);
        c.missions().setToolPermission(floor42(), m, "Bash", ActionCategory.EXECUTE, Decision.ASK);
        c.missions().setToolPermission(floor42(), m, "Write", ActionCategory.WRITE, Decision.DENY);
        c.missions().setToolPermission(floor42(), m, "Edit", ActionCategory.WRITE, Decision.DENY);
        c.missions()
                .addPermission(floor42(), m, PermissionRule.forTicket(t, "Edit", ActionCategory.WRITE, Decision.ASK));
        c.missions().addPermission(floor42(), m, new PermissionRule(DARIUS, ActionCategory.WRITE, null, Decision.DENY));
        c.missions().submitPlan(floor42(), m);
        c.missions().approvePlan(director(), m);
        c.tickets().start(agent(CHRIS), t);
    }

    @Test
    void thePanelListsEveryRuleBeyondTheLineWithWhoRecordedIt() {
        c.missions().setToolPermission(director(), m, "Write", ActionCategory.WRITE, Decision.ALLOW);

        ToolSettingView edit = view("Edit");
        assertThat(edit.decision()).isEqualTo("DENY");
        assertThat(edit.lineBy()).isEqualTo(PermissionViews.FLOOR42);
        assertThat(edit.rules())
                .containsExactly(
                        new RuleView(
                                "ruleTicket",
                                Map.of("decision", "ASK", "target", "Edit", "agent", "", "ticket", t.value()),
                                PermissionViews.FLOOR42),
                        new RuleView(
                                "ruleAgent",
                                Map.of("decision", "DENY", "target", "WRITE", "agent", "darius", "ticket", ""),
                                PermissionViews.FLOOR42));
        ToolSettingView write = view("Write");
        assertThat(write.lineBy()).isEqualTo(PermissionViews.DIRECTOR);
        assertThat(write.rules())
                .as("the reviewer's denial for every write stays listed; the ticket has no Write line")
                .extracting(RuleView::code)
                .containsExactly("ruleAgent");
        assertThat(view("Read").lineBy()).as("no line: nothing allows it").isNull();
    }

    @Test
    void anApprovalQuestionNamesTheRuleThatMadeItAsk() {
        c.actions().request(agent(CHRIS), new ActionId("b1"), m, t, CHRIS, ActionCategory.EXECUTE, "Bash", "mvn test");
        c.actions().request(agent(CHRIS), new ActionId("e1"), m, t, CHRIS, ActionCategory.WRITE, "Edit", "pom.xml");
        Question bash = question("b1");
        Question edit = question("e1");

        QuestionView onBash = QuestionView.of(c, bash);
        assertThat(onBash.causeCode()).isEqualTo("rule");
        assertThat(onBash.causeParams()).containsEntry("decision", "ASK").containsEntry("target", "Bash");
        assertThat(onBash.causeBy()).isEqualTo(PermissionViews.FLOOR42);
        QuestionView onEdit = QuestionView.of(c, edit);
        assertThat(onEdit.causeCode()).isEqualTo("ruleTicket");
        assertThat(onEdit.causeParams()).containsEntry("ticket", t.value());

        c.desk().answer(director(), bash.id(), "ALLOW");
        assertThat(QuestionView.of(c, question("b1")).causeCode())
                .as("once answered, the table may have changed: no cause is claimed")
                .isNull();
    }

    private ToolSettingView view(String tool) {
        return ToolSettingView.of(c, c.missions().find(m).orElseThrow(), tool);
    }

    private Question question(String action) {
        return c.desk().of(m).stream()
                .filter(q -> q.action() != null && q.action().value().equals(action))
                .findFirst()
                .orElseThrow();
    }

    private CommandContext director() {
        return ctx(Actor.director());
    }

    private CommandContext floor42() {
        return ctx(Actor.system());
    }

    private CommandContext agent(AgentId id) {
        return ctx(Actor.agent(id));
    }

    private CommandContext ctx(Actor actor) {
        now = now.plusSeconds(1);
        return new CommandContext(actor, Origin.SIMULATED, now, null);
    }
}
