package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The tickets of the missions: created, proposed by a planner and approved, worked, handed in for an independent
 * review, cancelled. Reached by {@link Company#tickets()}; the rules live in the ticket board, its reviews and its
 * planning.
 */
public final class TicketsFacade {

    private final TicketBoard ticketBoard;
    private final TicketReviews ticketReviews;
    private final TicketPlanning ticketPlanning;

    TicketsFacade(TicketBoard ticketBoard, TicketReviews ticketReviews, TicketPlanning ticketPlanning) {
        this.ticketBoard = ticketBoard;
        this.ticketReviews = ticketReviews;
        this.ticketPlanning = ticketPlanning;
    }

    public List<Ticket> of(MissionId mission) {
        return ticketBoard.tickets(mission);
    }

    /** A ticket number held by a journal row this version could not read: no new ticket takes it. */
    public void reserve(TicketId id) {
        ticketBoard.reserve(id);
    }

    public Optional<Ticket> find(TicketId id) {
        return ticketBoard.ticket(id);
    }

    public List<EventEnvelope> create(
            CommandContext ctx,
            MissionId id,
            String title,
            String description,
            Priority priority,
            List<TicketId> dependsOn,
            List<String> acceptanceCriteria) {
        return create(ctx, id, title, description, priority, dependsOn, acceptanceCriteria, TicketKind.WORK);
    }

    /** @param kind what the ticket is for, recorded with it: a planning ticket is never recognised by its title. */
    public List<EventEnvelope> create(
            CommandContext ctx,
            MissionId id,
            String title,
            String description,
            Priority priority,
            List<TicketId> dependsOn,
            List<String> acceptanceCriteria,
            TicketKind kind) {
        return ticketBoard.create(ctx, id, title, description, priority, dependsOn, acceptanceCriteria, kind);
    }

    public List<EventEnvelope> assign(CommandContext ctx, TicketId id, AgentId agent) {
        return ticketBoard.assign(ctx, id, agent);
    }

    public List<EventEnvelope> start(CommandContext ctx, TicketId id) {
        return ticketBoard.start(ctx, id);
    }

    /**
     * A ticket in progress whose assignee has left goes back to the queue, for another developer to take: a review
     * that rejected the delivery of someone who has gone sends the ticket back to work nobody would do.
     */
    public List<EventEnvelope> release(CommandContext ctx, TicketId id) {
        return ticketBoard.release(ctx, id);
    }

    public List<EventEnvelope> submitForReview(CommandContext ctx, TicketId id, String deliverable) {
        return ticketReviews.submit(ctx, id, deliverable);
    }

    /**
     * A reviewer in doubt asks for help before concluding: a peer's advice or a web research, once per review round
     * (ADR-0018). The review itself is given afterwards, with the answer in hand.
     */
    public List<EventEnvelope> requestConsultation(
            CommandContext ctx, TicketId id, ConsultationKind kind, String role, String question) {
        return ticketReviews.requestConsultation(ctx, id, kind, role, question);
    }

    /** The consulted agent's answer, or Floor42's own when the research was not allowed (by = null). */
    public List<EventEnvelope> answerConsultation(CommandContext ctx, TicketId id, String answer) {
        return ticketReviews.answerConsultation(ctx, id, answer);
    }

    /**
     * Independent review (spec section 12): the reviewer is an agent other than the producer. Only a validating verdict
     * closes the ticket; an unverifiable one is escalated to the director.
     */
    public List<EventEnvelope> review(CommandContext ctx, TicketId id, ReviewVerdict verdict, String findings) {
        return ticketReviews.review(ctx, id, verdict, findings);
    }

    /**
     * A review whose items Floor42 sorts against the ticket's criteria (ADR-0053): a reservation cites a criterion and
     * says what to change, anything else is a note. The verdict is recorded as declared.
     */
    public List<EventEnvelope> review(
            CommandContext ctx, TicketId id, ReviewVerdict declared, String findings, List<ReviewClaim> claims) {
        return ticketReviews.review(ctx, id, declared, findings, java.util.Objects.requireNonNull(claims, "claims"));
    }

    /**
     * A verdict given at a position of the ticket's review (ADR-0052): recorded only while the ticket still stands at
     * {@code at}, else refused ({@code STEP_MOVED_ON}) and nothing reaches the journal. Required on a mission whose
     * pinned workflow holds a check; null keeps the unguarded review of every other mission.
     */
    public List<EventEnvelope> review(
            CommandContext ctx,
            TicketId id,
            ReviewVerdict declared,
            String findings,
            List<ReviewClaim> claims,
            dev.floor42.domain.workflow.StepAt at) {
        return ticketReviews.review(
                ctx, id, declared, findings, java.util.Objects.requireNonNull(claims, "claims"), at);
    }

    /** The ticket's submissions and the submission of each of its reviews, derived (ADR-0052). */
    public ReviewRounds reviewRounds(TicketId id) {
        return ticketBoard.reviewRounds(id);
    }

    /** Graph mode (ADR-0062): a done node whose dependency was reopened since: its work may no longer fit. */
    public boolean toRecheck(TicketId id) {
        return ticketBoard.toRecheck(id);
    }

    /** The director approves proposed tickets: each becomes work to do, in plan order. */
    public List<EventEnvelope> approve(CommandContext ctx, MissionId id, List<TicketId> selection) {
        return ticketBoard.approve(ctx, id, selection);
    }

    /** As {@link #approve(CommandContext, MissionId, List)}; {@code risks} are the director's choices for graph nodes. */
    public List<EventEnvelope> approve(
            CommandContext ctx, MissionId id, List<TicketId> selection, java.util.Map<TicketId, String> risks) {
        return ticketBoard.approve(ctx, id, selection, risks);
    }

    /** The director rewrites a ticket before work starts on it. */
    public List<EventEnvelope> edit(
            CommandContext ctx, TicketId id, String title, String description, List<String> acceptanceCriteria) {
        return ticketBoard.edit(ctx, id, title, description, acceptanceCriteria);
    }

    /**
     * The planner hands in its plan: each proposal becomes a PROPOSED ticket for the producer, and the planning ticket
     * is done. Nothing proposed runs before the director approves it.
     */
    public List<EventEnvelope> propose(
            CommandContext ctx, TicketId planning, AgentId producer, List<ProposedTicket> proposals) {
        return ticketPlanning.propose(ctx, planning, producer, proposals);
    }

    public List<EventEnvelope> cancel(CommandContext ctx, TicketId id, String reason) {
        return ticketBoard.cancel(ctx, id, reason);
    }
}
