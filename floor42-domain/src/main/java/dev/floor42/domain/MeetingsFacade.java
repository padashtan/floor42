package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The director's meetings at the table (ADR-0047): the director calls agents about a mission, tells them things, each
 * or all, and closes the meeting; the attendees take no new step meanwhile, and their next brief restates what was
 * said. Reached by {@link Company#meetings()}; the rules live in the meetings area.
 */
public final class MeetingsFacade {

    /** The least a turn of speech may be allowed (ADR-0049): a cap must leave that much above what was charged. */
    public static final double MIN_TURN_USD = 0.05;

    private final Meetings meetings;
    private final WorkshopFacade workshop;

    MeetingsFacade(Meetings meetings, WorkshopFacade workshop) {
        this.meetings = meetings;
        this.workshop = workshop;
    }

    /** A meeting number held by a journal row this version could not read: no new meeting takes it. */
    public void reserve(String id) {
        meetings.reserve(id);
    }

    /** Every meeting, oldest first. */
    public List<Meeting> all() {
        return meetings.all();
    }

    public Optional<Meeting> find(String id) {
        return meetings.meeting(id);
    }

    /** The meetings about this mission, oldest first. */
    public List<Meeting> of(MissionId mission) {
        return meetings.of(mission);
    }

    /** The meeting open about this mission, if any: one at a time. */
    public Optional<Meeting> openOn(MissionId mission) {
        return meetings.openOn(mission);
    }

    /** The open meeting this agent sits in: while it lasts, the agent starts nothing. */
    public Optional<Meeting> attending(AgentId agent) {
        return meetings.attending(agent);
    }

    /** What the director said to this agent, or to all, at the meetings of the mission it attended; oldest first. */
    public List<MeetingNote> notesFor(MissionId mission, AgentId agent) {
        return meetings.notesFor(mission, agent);
    }

    /**
     * The director calls these agents to the table about a mission that is not closed. Refused for an agent who left,
     * one sitting on a committee in session, or one already in a meeting; one meeting at a time per mission.
     */
    public List<EventEnvelope> call(CommandContext ctx, MissionId mission, List<AgentId> attendees, String agenda) {
        return call(ctx, mission, attendees, agenda, MeetingPlace.TABLE);
    }

    /** As {@link #call}, at the table the director chose: the attendees sit around that one (ADR-0049). */
    public List<EventEnvelope> call(
            CommandContext ctx, MissionId mission, List<AgentId> attendees, String agenda, MeetingPlace place) {
        return meetings.call(ctx, mission, attendees, agenda, place);
    }

    /**
     * The director asks these attendees for their view, one after the other in this order (ADR-0049). Paid: each turn is
     * a short session, and the meeting spends at most {@code capUsd} in all, as Claude Code estimates it. Refused while a
     * round is under way, on a mission not in progress, or with a cap not above what the meeting spent.
     */
    public List<EventEnvelope> askForViews(
            CommandContext ctx, String meeting, List<AgentId> speakers, double capUsd, String question) {
        return meetings.ask(ctx, meeting, speakers, capUsd, question);
    }

    /**
     * Floor42's runtime records the round's next speaker's turn: what it answered, or why it was not heard (exactly one).
     */
    public List<EventEnvelope> recordTurn(CommandContext ctx, String meeting, SpokenTurn turn) {
        return meetings.turn(ctx, meeting, turn);
    }

    /**
     * What the meeting's turns were charged so far, in USD, against its cap: each session's estimate by Claude Code,
     * or, for a turn it gave none for, the budget the turn was allowed (an upper bound). Never measured.
     */
    public double spentUsdCharged(Meeting meeting) {
        return Usd.usd(meetings.spentMicroUsd(meeting));
    }

    /** The meeting whose own ticket this is (ADR-0049), if any. */
    public Optional<Meeting> ofTicket(TicketId ticket) {
        return meetings.ofTicket(ticket);
    }

    /**
     * The director tells one attendee, or all ({@code attendee} null), something about the mission. With {@link
     * MeetingNoteUse#ALSO_DIRECTIVE} it is also a directive on each concerned attendee's ticket under way, by the
     * workshop's rules, checked before anything is recorded.
     */
    public List<EventEnvelope> note(
            CommandContext ctx, String meeting, AgentId attendee, String text, MeetingNoteUse use) {
        return meetings.note(ctx, meeting, attendee, text, use, new Meetings.Directives() {
            @Override
            public TicketId check(CommandContext c, TicketId ticket) {
                return workshop.checkDirective(c, ticket);
            }

            @Override
            public List<EventEnvelope> give(CommandContext c, TicketId ticket, String directive) {
                return workshop.giveDirective(c, ticket, directive);
            }
        });
    }

    /** The director closes the meeting: the attendees may work again. */
    public List<EventEnvelope> close(CommandContext ctx, String meeting) {
        return meetings.close(ctx, meeting);
    }
}
