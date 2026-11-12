package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A meeting the director called at the table about one mission (ADR-0047): who was called, what the director said to
 * each of them or to all, and when it closed. Its attendees take no new step while it is open.
 */
public final class Meeting {

    private final String id;
    private final MissionId mission;
    private final List<AgentId> attendees;
    private final String agenda;
    private final Instant calledAt;
    MeetingPlace place = MeetingPlace.TABLE;
    final List<MeetingNote> notes = new ArrayList<>();
    final List<MeetingRound> rounds = new ArrayList<>();
    Instant closedAt;
    /** The meeting's own ticket, opened by the first "Ask for their view" (ADR-0049); null before. */
    TicketId ticket;

    Meeting(String id, MissionId mission, List<AgentId> attendees, String agenda, Instant calledAt) {
        this.id = id;
        this.mission = mission;
        this.attendees = List.copyOf(attendees);
        this.agenda = agenda;
        this.calledAt = calledAt;
    }

    /** The table it sits at: the meeting table unless the director called it at the conference table. */
    public MeetingPlace place() {
        return place;
    }

    /** The ticket its turns of speech are recorded on; null until the director first asks for their view. */
    public TicketId ticket() {
        return ticket;
    }

    /** Each "Ask for their view", oldest first. */
    public List<MeetingRound> rounds() {
        return List.copyOf(rounds);
    }

    /** The round whose speakers have not all taken their turn, if any: one at a time. */
    public java.util.Optional<MeetingRound> roundUnderWay() {
        return rounds.isEmpty() || !rounds.getLast().underWay()
                ? java.util.Optional.empty()
                : java.util.Optional.of(rounds.getLast());
    }

    public String id() {
        return id;
    }

    public MissionId mission() {
        return mission;
    }

    public List<AgentId> attendees() {
        return attendees;
    }

    /** What the director wants to talk about; null when not said. */
    public String agenda() {
        return agenda;
    }

    public Instant calledAt() {
        return calledAt;
    }

    /** When the director closed it; null while it is open. */
    public Instant closedAt() {
        return closedAt;
    }

    public boolean open() {
        return closedAt == null;
    }

    public boolean attends(AgentId agent) {
        return attendees.contains(agent);
    }

    /** Everything the director said, in order. */
    public List<MeetingNote> notes() {
        return List.copyOf(notes);
    }

    /** What the director said to this attendee or to all. */
    public List<MeetingNote> notesFor(AgentId agent) {
        return notes.stream().filter(n -> n.concerns(agent)).toList();
    }
}
