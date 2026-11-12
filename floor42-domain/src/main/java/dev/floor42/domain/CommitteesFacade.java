package dev.floor42.domain;

import dev.floor42.domain.event.CommitteeSeatEvent.CommitteeSeatDefined;
import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The committees: convened as part of a plan, their members' contributions round after round, the director's own word
 * in the seat of their double, the analyst's questions, and the seats the director defines. Reached by
 * {@link Company#committees()}; the rules live in the committee hall and the committee seats.
 */
public final class CommitteesFacade {

    /** Longest lens a seat may have: it goes into every brief of the member. */
    public static final int SEAT_LENS_MAX = 2_000;

    private final CommitteeHall committeeHall;
    private final CommitteeSeats committeeSeats;

    CommitteesFacade(CommitteeHall committeeHall, CommitteeSeats committeeSeats) {
        this.committeeHall = committeeHall;
        this.committeeSeats = committeeSeats;
    }

    /** One committee of a mission, by its number (ADR-0046). */
    public Optional<Committee> find(CommitteeRef ref) {
        return committeeHall.committee(ref);
    }

    /** The mission's committees, oldest first: its history. */
    public List<Committee> of(MissionId mission) {
        return committeeHall.committees(mission);
    }

    /** The mission's latest committee: the one deliberating, or the last one ruled on. */
    public Optional<Committee> latest(MissionId mission) {
        List<Committee> held = committeeHall.committees(mission);
        return held.isEmpty() ? Optional.empty() : Optional.of(held.getLast());
    }

    /** The committee seats the director defined, in the order they were first defined. */
    public List<CommitteeSeat> seats() {
        return committeeSeats.committeeSeats();
    }

    /**
     * Part of a plan: the committee meets once the director approves it. The chair is the decision ticket's assignee.
     * It takes the mission's next number; a mission convenes another committee only once its latest is ruled on.
     */
    public List<EventEnvelope> convene(
            CommandContext ctx,
            MissionId id,
            TicketId ticket,
            String topic,
            List<AgentId> members,
            List<String> roles) {
        return convene(ctx, id, ticket, topic, members, roles, Committee.Format.ARCHITECTURE);
    }

    /** A committee run the given way: an idea committee is moderated and debates until agreement (or its limit). */
    public List<EventEnvelope> convene(
            CommandContext ctx,
            MissionId id,
            TicketId ticket,
            String topic,
            List<AgentId> members,
            List<String> roles,
            Committee.Format format) {
        return committeeHall.convene(ctx, id, ticket, topic, members, roles, format);
    }

    /**
     * A member contributes to the current step. When the last expected contribution arrives, Floor42 moves the
     * committee on: analysis to debate, debate to the chair's synthesis, synthesis to the director.
     */
    public List<EventEnvelope> contribute(
            CommandContext ctx,
            CommitteeRef ref,
            AgentId member,
            Committee.Kind kind,
            String text,
            String proposal,
            String position,
            String criticalObjection) {
        return contribute(ctx, ref, member, kind, text, proposal, position, criticalObjection, List.of());
    }

    /** @param questions what the member could not settle without the director (its QUESTIONS line) */
    public List<EventEnvelope> contribute(
            CommandContext ctx,
            CommitteeRef ref,
            AgentId member,
            Committee.Kind kind,
            String text,
            String proposal,
            String position,
            String criticalObjection,
            List<String> questions) {
        return committeeHall.contribute(ctx, ref, member, kind, text, proposal, position, criticalObjection, questions);
    }

    /**
     * The director speaks in person in the seat of their double, in the current analysis or debate round: the
     * contribution is recorded as theirs, and the members read it as the seat's latest from their next session on. Said
     * after the double in the same round, it replaces it as the seat's position; said before, the double does not speak
     * this round.
     */
    public List<EventEnvelope> contributeAsDirector(
            CommandContext ctx,
            CommitteeRef ref,
            String text,
            String proposal,
            String position,
            String criticalObjection) {
        return committeeHall.contributeAsDirector(ctx, ref, text, proposal, position, criticalObjection);
    }

    /**
     * The business analyst's note and its questions for the director, each blocking: the committee waits for every
     * answer, then debates once more with them. With no question (it settled them all itself), the record goes to the
     * director as it is.
     */
    public List<EventEnvelope> clarify(
            CommandContext ctx, CommitteeRef ref, String text, List<Committee.Clarification> asked) {
        return committeeHall.clarify(ctx, ref, text, asked);
    }

    /** The director sends the decision back with a constraint: one more debate round, then a new synthesis. */
    public List<EventEnvelope> sendBack(CommandContext ctx, CommitteeRef ref, String constraint) {
        return committeeHall.sendBack(ctx, ref, constraint);
    }

    /**
     * The director defines a committee seat of their own (or changes it, with the same key). Its member is hired on the
     * first committee that seats it, like every member, and only reads. Floor42 checks the key is none of its built-in
     * seats; the domain checks it names no other agent.
     */
    public List<EventEnvelope> defineSeat(
            CommandContext ctx,
            String key,
            String name,
            String title,
            String model,
            String effort,
            String lens,
            List<String> panels) {
        return committeeSeats.defineCommitteeSeat(
                ctx, new CommitteeSeatDefined(key, name, title, model, effort, lens, panels));
    }
}
