package dev.floor42.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One "Ask for their view" of the director's meeting (ADR-0049): who speaks, in that order, about what, and within
 * which cost cap for the whole meeting; then each speaker's turn as it is taken.
 */
public final class MeetingRound {

    private final int number;
    private final List<AgentId> speakers;
    private final double capUsd;
    private final String question;
    private final Instant askedAt;
    final Map<AgentId, MeetingTurn> turns = new LinkedHashMap<>();

    MeetingRound(int number, List<AgentId> speakers, double capUsd, String question, Instant askedAt) {
        this.number = number;
        this.speakers = List.copyOf(speakers);
        this.capUsd = capUsd;
        this.question = question;
        this.askedAt = askedAt;
    }

    public int number() {
        return number;
    }

    /** In the order the director gave: the order they speak in. */
    public List<AgentId> speakers() {
        return speakers;
    }

    /** The most the meeting may spend from this round on, as Claude Code estimates it. */
    public double capUsd() {
        return capUsd;
    }

    /** What the director asks this round; null when the agenda says it. */
    public String question() {
        return question;
    }

    public Instant askedAt() {
        return askedAt;
    }

    /** The turns taken, in the order they were. */
    public List<MeetingTurn> turns() {
        return List.copyOf(turns.values());
    }

    public Optional<MeetingTurn> turnOf(AgentId speaker) {
        return Optional.ofNullable(turns.get(speaker));
    }

    /** The next speaker who has not taken its turn; empty once everyone has. */
    public Optional<AgentId> next() {
        return speakers.stream().filter(s -> !turns.containsKey(s)).findFirst();
    }

    public boolean underWay() {
        return next().isPresent();
    }
}
