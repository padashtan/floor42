package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireNoAgentAtWork;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.workflow.Workflow.Step;
import java.util.ArrayList;
import java.util.List;

/**
 * The director's objections at acceptance become work in the same mission (ADR-0060). Each is checked before the first
 * event is recorded; all of them are one command and share one cause, {@code objections-<n>}, which names the round.
 * An objection about a done ticket is a directive that reopens it ({@link WorkshopFacade#giveDirective}); new work is a
 * ticket of the director's, approved, under the mission's permission table only.
 */
final class Objections {

    static final int MAX_OBJECTIONS = 10;
    static final int MAX_TEXT = 4_000;

    private final CompanyView view;
    private final ObjectionRounds rounds;
    private final TicketsFacade tickets;
    private final WorkshopFacade workshop;

    Objections(CompanyView view, ObjectionRounds rounds, TicketsFacade tickets, WorkshopFacade workshop) {
        this.view = view;
        this.rounds = rounds;
        this.tickets = tickets;
        this.workshop = workshop;
    }

    List<ObjectionRound> rounds(MissionId mission) {
        return rounds.of(mission);
    }

    List<EventEnvelope> object(CommandContext ctx, MissionId id, List<Objection> items) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, id);
        require(
                items != null && !items.isEmpty() && items.size() <= MAX_OBJECTIONS,
                Refusal.INVALID_ARGUMENT,
                "Between 1 and " + MAX_OBJECTIONS + " objections");
        requireAtAcceptance(m);
        requireNoAgentAtWork(view, id);
        for (Objection o : items) check(ctx, id, o);
        CommandContext round =
                new CommandContext(ctx.actor(), ctx.origin(), ctx.now(), ObjectionRounds.CAUSE + rounds.next(id));
        List<EventEnvelope> events = new ArrayList<>();
        for (Objection o : items) {
            String text = o.text().strip();
            events.addAll(
                    o.ticket() != null
                            ? workshop.giveDirective(round, o.ticket(), text)
                            : tickets.create(
                                    round,
                                    id,
                                    titleOf(text),
                                    text + "\n\nFrom the director's objection at acceptance.",
                                    Priority.NORMAL,
                                    List.of(),
                                    List.of("Done as the director's objection asks: " + text)));
        }
        return events;
    }

    /** The mission waits for the director's acceptance: in progress, every ticket done or dropped, one done at least. */
    private void requireAtAcceptance(Mission m) {
        List<Ticket> all = view.tickets(m.id());
        require(
                m.status == MissionStatus.IN_PROGRESS
                        && all.stream().allMatch(t -> t.status().isTerminal())
                        && all.stream().anyMatch(t -> t.status() == TicketStatus.DONE),
                Refusal.NOT_AT_ACCEPTANCE,
                m.id() + " is not waiting for the director's acceptance");
    }

    private void check(CommandContext ctx, MissionId id, Objection o) {
        requireText(o == null ? null : o.text(), "text");
        require(
                o.text().strip().length() <= MAX_TEXT,
                Refusal.INVALID_ARGUMENT,
                "An objection is at most " + MAX_TEXT + " characters");
        if (o.ticket() == null) {
            require(
                    view.workflowOf(id)
                            .runnable()
                            .map(w -> w.has(Step.Produce.class))
                            .orElse(false),
                    Refusal.NO_PRODUCTION_IN_WORKFLOW,
                    id + " follows a workflow that produces nothing: new work has nobody to do it");
            return;
        }
        Ticket t = view.existingTicket(o.ticket());
        require(t.mission().equals(id), Refusal.UNKNOWN_TICKET, o.ticket() + " is not a ticket of " + id);
        require(
                t.kind() == TicketKind.WORK && t.status() == TicketStatus.DONE,
                Refusal.INVALID_TRANSITION,
                o.ticket() + " is not a done work ticket");
        // The refusals of the directive itself (a ruled decision with no implementation), before any event.
        workshop.checkDirective(ctx, o.ticket());
    }

    private static String titleOf(String text) {
        String title = text.lines().findFirst().orElse(text);
        return title.length() > 80 ? title.substring(0, 79).strip() + "…" : title;
    }
}
