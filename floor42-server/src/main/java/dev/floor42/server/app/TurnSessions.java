package dev.floor42.server.app;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.TicketKind;
import java.util.List;

/**
 * Turns of speech at the director's meeting (ADR-0049) are sessions on the meeting's own ticket: their cost is the
 * mission's (it was spent on its money), but they are no work on it. Figures of work (session counts, failures, time
 * worked) leave them out; figures of spend keep them.
 */
public final class TurnSessions {

    private TurnSessions() {}

    /** A session on a meeting's own ticket: a turn of speech, not work. */
    public static boolean isTurn(Company c, AgentSession s) {
        return c.tickets()
                .find(s.ticket())
                .map(t -> t.kind() == TicketKind.MEETING)
                .orElse(false);
    }

    /** The sessions that were work: every one but the turns of speech. */
    public static List<AgentSession> work(Company c, List<AgentSession> sessions) {
        return sessions.stream().filter(s -> !isTurn(c, s)).toList();
    }
}
