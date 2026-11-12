package dev.floor42.server.app;

import static dev.floor42.server.app.SimulatedCrew.CHRIS;
import static dev.floor42.server.app.SimulatedCrew.GEOFFREY;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import java.time.Instant;
import java.util.List;

/**
 * A mission approved with Chris's ticket in progress and no session running: what the dispatcher resumes. Real, or a
 * rehearsal whose script is not running (a daemon restarted mid-replay). Built by hand, nothing plays it.
 */
public final class StalledMissions {

    public record Stalled(MissionId mission, TicketId ticket) {}

    private StalledMissions() {}

    public static Stalled of(OfficeService office, SimulatedCrew crew, String repository, Origin origin) {
        crew.hireIfMissing();
        CommandContext director = office.context(Actor.director(), origin);
        MissionId m = ((MissionCreated) office.execute(c -> origin == Origin.REAL
                                ? c.missions().create(director, "Stalled", "Resume me", repository, Workflow.BUILT_IN)
                                : c.missions().createRehearsal(director, "Stalled", "Resume me", repository))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        as(office, GEOFFREY, origin),
                                        m,
                                        "Inspect",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("Read")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director, t, CHRIS));
        office.execute(c -> c.missions().submitPlan(as(office, GEOFFREY, origin), m));
        office.execute(c -> c.verification().setBudget(director, m, 1.0));
        office.execute(c -> c.missions().approvePlan(director, m));
        office.execute(c -> c.tickets().start(as(office, CHRIS, origin), t));
        return new Stalled(m, t);
    }

    /** A session of Chris on the ticket, opened at {@code at} and still open. */
    public static void openSession(OfficeService office, Stalled s, Origin origin, String id, Instant at) {
        office.execute(c -> c.sessions()
                .start(
                        new CommandContext(Actor.system(), origin, at, null),
                        id,
                        s.ticket(),
                        CHRIS,
                        "claude-haiku-5-5",
                        "2.1.304"));
    }

    private static CommandContext as(OfficeService office, AgentId agent, Origin origin) {
        return office.context(Actor.agent(agent), origin);
    }
}
