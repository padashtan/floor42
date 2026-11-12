package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.WorkRole;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A real run gave every planned ticket to the first developer: the developers hired next stayed idle. Independent
 * tickets now go to the developers in turn, a chain of dependent ones stays with one, and the planner's brief names
 * the team by role.
 */
class TicketSpreadTest {

    static final AgentId DEV = new AgentId("dev");
    static final AgentId KEN = new AgentId("ken");

    static TicketCreated planned(String id, String... dependsOn) {
        return new TicketCreated(
                new TicketId(id),
                new MissionId("M-1"),
                "Ticket " + id,
                "",
                Priority.NORMAL,
                java.util.Arrays.stream(dependsOn).map(TicketId::new).toList(),
                List.of("done"),
                TicketKind.WORK);
    }

    @Test
    void independentTicketsGoToTheDevelopersInTurnAndAChainStaysWithOne() {
        Map<TicketId, AgentId> to = TicketSpread.of(
                List.of(planned("T-2"), planned("T-3"), planned("T-4", "T-2"), planned("T-5")), List.of(DEV, KEN));

        assertThat(to)
                .containsExactly(
                        Map.entry(new TicketId("T-2"), DEV),
                        Map.entry(new TicketId("T-3"), KEN),
                        Map.entry(new TicketId("T-4"), DEV),
                        Map.entry(new TicketId("T-5"), DEV));
    }

    @Test
    void aSingleDeveloperTakesEverything() {
        assertThat(TicketSpread.of(List.of(planned("T-2"), planned("T-3")), List.of(DEV)))
                .containsValues(DEV)
                .doesNotContainValue(KEN);
    }

    @Test
    void thePlannerSeesTheTeamByRole() {
        Company c = new Company();
        CommandContext director = new CommandContext(Actor.director(), Origin.REAL, Instant.now(), null);
        c.staff().hire(director, DEV, "Dev", "Developer", WorkRole.DEVELOPER, "claude-haiku-5-5", "low");
        c.staff().hire(director, KEN, "Ken", "Developer", WorkRole.DEVELOPER, "claude-haiku-5-5", "low");
        c.staff().hire(director, new AgentId("ria"), "Ria", "Reviewer", WorkRole.REVIEWER, "claude-haiku-5-5", "low");

        assertThat(TicketSpread.team(c)).isEqualTo("Dev (developer), Ken (developer), Ria (reviewer)");
        assertThat(TicketSpread.developers(c, KEN)).containsExactly(KEN, DEV);
    }
}
