package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A small company with three agents, used by the domain tests. Keeps every recorded event, like the journal would. */
final class Fixture {

    static final AgentId GALEN = new AgentId("galen");
    static final AgentId CHRIS = new AgentId("chris");
    static final AgentId DARIUS = new AgentId("darius");

    /** The fixtures made by the test running on this thread, for its golden master (GoldenFixtureExtension). */
    private static final ThreadLocal<List<Fixture>> MADE = ThreadLocal.withInitial(ArrayList::new);

    final Company company = new Company();
    final List<EventEnvelope> journal = new ArrayList<>();
    /** Where each recorded command ends in the journal. */
    final List<Integer> commandEnds = new ArrayList<>();

    final Origin origin;
    private Instant now = Instant.parse("2026-10-20T09:00:00Z");

    Fixture() {
        this(Origin.SIMULATED);
    }

    Fixture(Origin origin) {
        this.origin = origin;
        MADE.get().add(this);
        for (AgentId id : List.of(GALEN, CHRIS, DARIUS)) {
            record(company.staff()
                    .hire(
                            director(),
                            id,
                            id.value(),
                            "role of " + id,
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of()));
        }
    }

    CommandContext director() {
        return ctx(Actor.director());
    }

    CommandContext agent(AgentId id) {
        return ctx(Actor.agent(id));
    }

    CommandContext system() {
        return ctx(Actor.system());
    }

    private CommandContext ctx(Actor actor) {
        now = now.plusSeconds(1);
        return new CommandContext(actor, origin, now, null);
    }

    List<EventEnvelope> record(List<EventEnvelope> events) {
        journal.addAll(events);
        commandEnds.add(journal.size());
        return events;
    }

    /** The fixtures made since the last call on this thread, oldest first. */
    static List<Fixture> takeMade() {
        List<Fixture> made = List.copyOf(MADE.get());
        MADE.get().clear();
        return made;
    }

    MissionId mission() {
        var created = origin == Origin.REAL
                ? company.missions()
                        .create(
                                director(),
                                "Secure the APIs",
                                "Protect web services with a gateway",
                                "C:/repo/api",
                                dev.floor42.domain.workflow.Workflow.BUILT_IN)
                : company.missions().create(director(), "Secure the APIs", "Protect web services with a gateway");
        return (MissionId) ((dev.floor42.domain.event.MissionEvent.MissionCreated)
                        record(created).getFirst().event())
                .mission();
    }

    TicketId ticket(MissionId mission, String title, TicketId... dependsOn) {
        var events = record(company.tickets()
                .create(agent(GALEN), mission, title, "", Priority.NORMAL, List.of(dependsOn), List.of("criterion")));
        return ((dev.floor42.domain.event.TicketEvent.TicketCreated)
                        events.getFirst().event())
                .ticket();
    }

    /** Mission approved, with one ticket assigned to Chris and started. */
    MissionId runningMission() {
        MissionId m = mission();
        TicketId t = ticket(m, "Validate token audience");
        record(company.tickets().assign(director(), t, CHRIS));
        record(company.missions().submitPlan(agent(GALEN), m));
        if (origin == Origin.REAL) record(company.verification().setBudget(director(), m, null));
        record(company.missions().approvePlan(director(), m));
        record(company.tickets().start(agent(CHRIS), t));
        return m;
    }

    TicketId firstTicket(MissionId m) {
        return company.tickets().of(m).getFirst().id();
    }

    void allow(MissionId m, ActionCategory category, String prefix, AgentId agent) {
        record(company.missions()
                .addPermission(director(), m, new PermissionRule(agent, category, prefix, Decision.ALLOW)));
    }
}
