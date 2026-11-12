package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketKind;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * "Extend the graph" (ADR-0062): on the director's click, one read-only planner session is given the mission's graph as
 * it stands and proposes at most {@link Workflow.Step.Plan#MAX_TICKETS} more nodes, which may depend on the nodes
 * already there. They arrive as proposals the director approves as any plan. Nothing done is reopened. The session is
 * paid, and the screen says so before the click. The extension is a planning ticket of the mission, so the journal holds
 * it without any new event.
 */
@ApplicationScoped
public class GraphExtensions {

    /** The title of an extension's planning ticket: how the planner's brief and its parser tell it from the first plan. */
    static final String TITLE = "Extend the graph";

    @Inject
    OfficeService office;

    @Inject
    Dispatcher dispatcher;

    @Inject
    StaffProvisioner staff;

    /** Whether this planning ticket extends the graph. */
    static boolean is(Ticket t) {
        return t != null && t.kind() == TicketKind.PLANNING && t.title().startsWith(TITLE);
    }

    /** Opens the extension and asks the dispatcher to start the planner; returns the planning ticket. */
    public TicketId start(MissionId m, String goal) {
        staff.requirePresent(StaffProvisioner.PLANNER, "Geoffrey, the planner");
        staff.ensurePlanner();
        String wanted = goal == null || goal.isBlank() ? "" : goal.strip();
        List<String> criteria = List.of(
                "At most " + Workflow.Step.Plan.MAX_TICKETS + " new nodes, each with a checkable result",
                "A new node may depend on a node already there; no node already there is changed");
        // One command: the dispatcher must never see the ticket before it is the planner's (it would be produced).
        List<EventEnvelope> events = office.execute(c -> {
            refuseUnlessReady(c, m);
            List<EventEnvelope> all = new ArrayList<>(c.tickets()
                    .create(
                            office.context(Actor.director(), Origin.REAL),
                            m,
                            TITLE,
                            wanted,
                            Priority.NORMAL,
                            List.of(),
                            criteria,
                            TicketKind.PLANNING));
            TicketId id = ((TicketCreated) all.getFirst().event()).ticket();
            all.addAll(c.tickets().assign(office.context(Actor.system(), Origin.REAL), id, StaffProvisioner.PLANNER));
            return all;
        });
        dispatcher.nudge(m);
        return ((TicketCreated) events.getFirst().event()).ticket();
    }

    /** A graph mission under way, with no extension still being planned. */
    private static void refuseUnlessReady(Company c, MissionId m) {
        Mission mission =
                c.missions().find(m).orElseThrow(() -> RefusedException.invalid("UNKNOWN_MISSION", "No mission " + m));
        if (mission.origin() != Origin.REAL
                || !c.workflows().of(m).runnable().map(Workflow::graph).orElse(false)) {
            throw RefusedException.conflict("NOT_A_GRAPH_MISSION", m + " does not work as a graph");
        }
        if (mission.status() != MissionStatus.IN_PROGRESS) {
            throw RefusedException.conflict(
                    "MISSION_NOT_ACTIVE", "A graph is extended while the mission is in progress");
        }
        boolean planning = c.tickets().of(m).stream()
                .anyMatch(t -> is(t) && t.status() != TicketStatus.DONE && t.status() != TicketStatus.CANCELLED);
        if (planning) {
            throw RefusedException.conflict("EXTENSION_RUNNING", "The graph of " + m + " is already being extended");
        }
    }

    /** The graph as the planner is shown it: each node with its status, what it depends on and what it must show. */
    static String describe(Company c, MissionId m) {
        StringBuilder out = new StringBuilder();
        for (Ticket t : c.tickets().of(m)) {
            if (t.kind() != TicketKind.WORK || t.status() == TicketStatus.CANCELLED) continue;
            out.append("- ")
                    .append(t.id())
                    .append(" [")
                    .append(t.status())
                    .append("] ")
                    .append(t.title());
            if (!t.dependsOn().isEmpty()) {
                out.append(" (after ")
                        .append(String.join(
                                ", ",
                                t.dependsOn().stream().map(TicketId::value).toList()))
                        .append(")");
            }
            out.append("\n");
            t.acceptanceCriteria()
                    .forEach(k -> out.append("    done when: ").append(k).append("\n"));
        }
        return out.toString();
    }

    /** The brief of an extension's planner session. */
    static String brief(Company c, Mission m, Ticket t, dev.floor42.domain.Agent agent) {
        return BriefTemplate.named("planner-extend")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("objective", m.objective())
                .with(
                        "goal",
                        t.description().isBlank() ? "none given: add what the objective still lacks" : t.description())
                .with("graph", describe(c, m.id()))
                .with("team", TicketSpread.team(c))
                .with("maxTickets", PlannerBrief.maxTickets(c, m.id()))
                .render();
    }
}
