package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCompleted;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import java.util.ArrayList;
import java.util.List;

/**
 * The planner hands in its plan (ADR-0017): each proposal becomes a ticket waiting for the director's approval. The
 * tickets are the {@link TicketBoard}'s, which applies these events.
 */
final class TicketPlanning {

    private final CompanyView view;

    TicketPlanning(CompanyView view) {
        this.view = view;
    }

    /** Each proposal becomes a PROPOSED ticket for the producer, and the planning ticket is done. */
    List<EventEnvelope> propose(
            CommandContext ctx, TicketId planning, AgentId producer, List<ProposedTicket> proposals) {
        Ticket p = view.existingTicket(planning);
        Mission m = view.missionFor(ctx, p.mission());
        require(m.ticketValidation, Refusal.NO_VALIDATION, m.id() + " does not validate tickets");
        require(
                p.assignee != null && ctx.actor().is(p.assignee),
                Refusal.NOT_ASSIGNEE,
                "Only the planner hands in " + planning);
        require(p.status == TicketStatus.IN_PROGRESS, Refusal.INVALID_TRANSITION, planning + " is " + p.status);
        require(proposals != null && !proposals.isEmpty(), Refusal.EMPTY_PLAN, "A plan needs at least one ticket");
        require(proposals.size() <= 8, Refusal.PLAN_TOO_LONG, "A plan has at most 8 tickets");
        require(
                !view.existingAgent(producer).suspended,
                Refusal.AGENT_SUSPENDED,
                producer + " takes no new assignments");
        checkProposals(proposals);
        checkExisting(m.id(), proposals);
        Recorder r = view.record(ctx, m.id());
        List<TicketId> created = new ArrayList<>();
        for (ProposedTicket x : proposals) {
            TicketId id = view.nextTicketId();
            List<TicketId> after = new ArrayList<>(x.existing());
            dependencies(x).stream().map(created::get).forEach(after::add);
            r.emit(proposed(id, m.id(), x, after));
            r.as(Actor.system()).emit(new TicketAssigned(id, producer));
            created.add(id);
        }
        r.as(Actor.system()).emit(new TicketCompleted(planning));
        return r.events();
    }

    /** Every proposal is checked before the first event (emitting applies at once). */
    private static void checkProposals(List<ProposedTicket> proposals) {
        for (int i = 0; i < proposals.size(); i++) {
            requireText(proposals.get(i).title(), "title");
            for (int d : dependencies(proposals.get(i))) {
                require(d >= 0 && d < i, Refusal.UNKNOWN_DEPENDENCY, "Ticket " + (i + 1) + " depends on a later one");
            }
        }
    }

    /** A graph extension builds on tickets of this mission that stand: never a cancelled one, never another mission's. */
    private void checkExisting(MissionId mission, List<ProposedTicket> proposals) {
        for (ProposedTicket x : proposals) {
            for (TicketId dep : x.existing()) {
                Ticket d = view.ticket(dep).orElse(null);
                require(
                        d != null
                                && d.mission().equals(mission)
                                && d.kind() == TicketKind.WORK
                                && d.status != TicketStatus.CANCELLED,
                        Refusal.UNKNOWN_DEPENDENCY,
                        dep + " is not a ticket of " + mission + " to build on");
            }
        }
    }

    private static List<Integer> dependencies(ProposedTicket x) {
        return x.dependsOn() == null ? List.of() : x.dependsOn();
    }

    private static TicketCreated proposed(TicketId id, MissionId mission, ProposedTicket x, List<TicketId> dependsOn) {
        return new TicketCreated(
                id,
                mission,
                x.title().strip(),
                x.description() == null ? "" : x.description().strip(),
                Priority.NORMAL,
                dependsOn,
                x.acceptanceCriteria() == null ? List.of() : x.acceptanceCriteria(),
                TicketKind.WORK);
    }
}
