package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the implementation ticket of an adopted decision gets: the defaults, except where the director chose. */
class ImplementationDefaultsTest {

    private final Company c = Company.replay(List.of());
    private Instant now = Instant.parse("2026-11-03T09:00:00Z");
    private final MissionId m;

    ImplementationDefaultsTest() {
        m = ((MissionCreated) c.missions()
                        .create(director(), "Gateway", "Decide")
                        .getFirst()
                        .event())
                .mission();
        ticket("Decide: the gateway");
        for (String tool : List.of("Write", "Edit")) {
            c.missions().setToolPermission(floor42(), m, tool, ActionCategory.WRITE, Decision.DENY);
        }
        c.missions().submitPlan(floor42(), m);
        c.missions().approvePlan(director(), m);
    }

    @Test
    void theDirectorsClickOnTheCommitteesOwnLineMakesItTheirs() {
        TicketId before = ticket("Implement: first");
        assertThat(Implementation.defaultsFor(c, m, before))
                .as("the committee's Write line gives way to the default")
                .contains(PermissionRule.forTicket(before, "Write", ActionCategory.WRITE, Decision.ASK));

        // The director clicks Refuse, the value already shown: it becomes the director's choice.
        c.missions().setToolPermission(director(), m, "Write", ActionCategory.WRITE, Decision.DENY);
        TicketId after = ticket("Implement: second");

        assertThat(Implementation.defaultsFor(c, m, after))
                .as("Write stays refused on the implementation; Edit still gets the default")
                .contains(PermissionRule.forTicket(after, "Edit", ActionCategory.WRITE, Decision.ASK))
                .noneMatch(r -> "Write".equals(r.tool()));
        assertThat(c.missions()
                        .find(m)
                        .orElseThrow()
                        .policy()
                        .author(PermissionRule.forTool("Write", ActionCategory.WRITE, Decision.DENY)))
                .contains(Actor.director());
    }

    private TicketId ticket(String title) {
        return ((TicketCreated) c.tickets()
                        .create(floor42(), m, title, "", Priority.NORMAL, List.of(), List.of("done"))
                        .getFirst()
                        .event())
                .ticket();
    }

    private CommandContext director() {
        return ctx(Actor.director());
    }

    private CommandContext floor42() {
        return ctx(Actor.system());
    }

    private CommandContext ctx(Actor actor) {
        now = now.plusSeconds(1);
        return new CommandContext(actor, Origin.SIMULATED, now, null);
    }
}
