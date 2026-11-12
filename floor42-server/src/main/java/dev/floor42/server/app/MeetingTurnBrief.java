package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Meeting;
import dev.floor42.domain.MeetingNote;
import dev.floor42.domain.MeetingRound;
import dev.floor42.domain.MeetingTurn;
import dev.floor42.domain.Mission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The brief of a turn of speech at the director's meeting (ADR-0049), from the journal only: the mission, the agenda,
 * the round's question, and what was said so far (the director's notes and the answers heard), oldest first, the
 * oldest left out past {@link #MAX_SAID} characters.
 */
final class MeetingTurnBrief {

    static final int MAX_SAID = 12_000;
    static final String LEFT_OUT = "(earlier words left out: the meeting said more than this brief holds)";

    private MeetingTurnBrief() {}

    /** One thing said, at the time the journal recorded it. */
    private record Said(Instant at, String line) {}

    static String of(Company c, Meeting mt, MeetingRound round, Agent speaker, String folder) {
        Mission m = c.missions().find(mt.mission()).orElseThrow();
        return BriefTemplate.named("meeting-turn")
                .with("agent", speaker.displayName())
                .with("title", speaker.role())
                .with("meeting", mt.id())
                .with("mission", m.id())
                .with("missionTitle", m.title())
                .with("objective", m.objective())
                .with("question", question(mt, round))
                .with("folder", folder)
                .with("said", said(c, mt))
                .render();
    }

    private static String question(Meeting mt, MeetingRound round) {
        if (round.question() != null) return "The question: " + round.question();
        if (mt.agenda() != null) return "The agenda: " + mt.agenda();
        return "Say what you think of the mission as it stands.";
    }

    /** The notes and the answers heard, oldest first; "nothing yet" before the first word. */
    static String said(Company c, Meeting mt) {
        List<Said> all = new ArrayList<>();
        for (MeetingNote n : mt.notes())
            all.add(new Said(n.at(), "The director, to " + to(c, n.attendee()) + ": " + n.text()));
        for (MeetingRound r : mt.rounds()) {
            for (MeetingTurn t : r.turns()) {
                if (t.heard()) all.add(new Said(t.at(), name(c, t.attendee()) + ": " + t.answer()));
            }
        }
        all.sort(Comparator.comparing(Said::at));
        return fit(all.stream().map(Said::line).toList());
    }

    /** The newest lines that fit in {@link #MAX_SAID}, in their order; a line says when older ones were left out. */
    static String fit(List<String> lines) {
        if (lines.isEmpty()) return "nothing yet: you speak first.";
        List<String> kept = new ArrayList<>();
        int size = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (size + line.length() > MAX_SAID && !kept.isEmpty()) {
                kept.addFirst(LEFT_OUT);
                break;
            }
            kept.addFirst(line);
            size += line.length() + 1;
        }
        return String.join("\n", kept);
    }

    private static String to(Company c, AgentId attendee) {
        return attendee == null ? "everyone" : name(c, attendee);
    }

    private static String name(Company c, AgentId agent) {
        return c.staff().find(agent).map(Agent::displayName).orElse(agent.value());
    }
}
