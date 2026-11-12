package dev.floor42.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The end-of-mission retrospective (ADR-0020): the agents who worked on the mission meet at the table once it has
 * ended, each says what went well and what did not from the journal's facts, and the chair concludes with lessons the
 * director validates. What is shown comes from persisted contributions, never from a script.
 */
public final class Retro {

    /**
     * What one participant said. {@code skipped} is null when it spoke; otherwise why it said nothing (its session
     * failed, for instance) and the other fields are empty.
     */
    public record Contribution(
            AgentId agent, List<String> wentWell, List<String> wentBadly, String text, String skipped, Instant at) {

        public Contribution {
            wentWell = List.copyOf(wentWell);
            wentBadly = List.copyOf(wentBadly);
        }
    }

    private final MissionId mission;
    private final TicketId ticket;
    private final AgentId chair;
    private final List<AgentId> participants;
    private final String facts;
    final Map<AgentId, Contribution> contributions = new LinkedHashMap<>();
    List<String> conclusions = new ArrayList<>();
    String text;
    String reason;
    boolean concluded;

    Retro(MissionId mission, TicketId ticket, AgentId chair, List<AgentId> participants, String facts) {
        this.mission = mission;
        this.ticket = ticket;
        this.chair = chair;
        this.participants = List.copyOf(participants);
        this.facts = facts;
    }

    public MissionId mission() {
        return mission;
    }

    /** The retrospective's own ticket: the sessions of the retrospective are recorded on it. */
    public TicketId ticket() {
        return ticket;
    }

    public AgentId chair() {
        return chair;
    }

    public List<AgentId> participants() {
        return participants;
    }

    /** The facts Floor42 gave every participant, composed from the journal when the retrospective opened. */
    public String facts() {
        return facts;
    }

    public List<Contribution> contributions() {
        return List.copyOf(contributions.values());
    }

    /** Participants who have not spoken yet, in order. */
    public List<AgentId> pending() {
        return participants.stream().filter(a -> !contributions.containsKey(a)).toList();
    }

    public List<String> conclusions() {
        return List.copyOf(conclusions);
    }

    /** The chair's full conclusion; null when the chair could not conclude (see {@link #reason()}). */
    public String text() {
        return text;
    }

    /** Why the retrospective ended without the chair's conclusion; null otherwise. */
    public String reason() {
        return reason;
    }

    public boolean concluded() {
        return concluded;
    }

    public boolean isParticipant(AgentId agent) {
        return participants.contains(agent);
    }
}
