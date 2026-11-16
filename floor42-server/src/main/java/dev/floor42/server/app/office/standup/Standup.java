package dev.floor42.server.app.office.standup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The 9 o'clock standup (ADR-0078): per agent, what the journal says about the last day with activity, about now, and
 * about what stops the agent. Codes and ids only; {@code text} is a ticket title or a question subject, agent or
 * director text, shown as plain text.
 *
 * @param date today, in {@code zone}
 * @param previousDay the last local day before today holding a real event, null when there is none
 */
public record Standup(String date, String previousDay, String zone, List<AgentStandup> agents) {

    /**
     * One line: {@code code} is SUBMITTED, COMPLETED, SESSIONS, SESSIONS_FAILED, IN_PROGRESS, IN_REVIEW, QUEUED,
     * SUSPENDED, QUESTION or DEPENDENCY. {@code count} is the number of sessions, or of unmet dependencies; null else.
     */
    public record Line(String code, String mission, String ticket, String text, Integer count) {}

    public record AgentStandup(String agent, String name, List<Line> yesterday, List<Line> today, List<Line> blocked) {
        public AgentStandup {
            blocked = blocked == null ? null : Collections.unmodifiableList(new ArrayList<>(blocked));
            today = today == null ? null : Collections.unmodifiableList(new ArrayList<>(today));
            yesterday = yesterday == null ? null : Collections.unmodifiableList(new ArrayList<>(yesterday));
        }
    }

    public Standup {
        agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
    }
}
