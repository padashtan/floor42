package dev.floor42.server.app;

import dev.floor42.server.runtime.SessionReport;

/**
 * What {@link SessionGate#runUnheld} gives back (a retrospective's turn, a staffing review): the session's report, or
 * why no session started, each worded as what really happened, never a reason that did not hold.
 */
sealed interface Unheld {

    /** The session ran: its report, failed or not. */
    record Ran(SessionReport report) implements Unheld {}

    /** No session started, for this reason. */
    record NotStarted(Reason reason) implements Unheld {}

    /** What the result says: the text the agent delivered, or why there is none (never both). */
    record Answer(String text, String why) {
        boolean heard() {
            return text != null;
        }
    }

    /** The session's text when it delivered one; otherwise the true reason, as the journal records it. */
    static Answer answerOf(Unheld result) {
        return switch (result) {
            case NotStarted refused -> new Answer(null, refused.reason().why());
            case Ran ran -> {
                SessionReport report = ran.report();
                String text = report.finalText();
                if (!report.failed() && text != null && !text.isBlank()) yield new Answer(text, null);
                String notes = String.join("; ", report.notes());
                yield new Answer(null, "the session failed" + (notes.isEmpty() ? "" : ": " + notes));
            }
        };
    }

    enum Reason {
        /** The origin rule: a simulated mission never gets a live session. */
        SIMULATED_MISSION("no live session for a simulated mission"),
        /** The agent attends an open meeting of the director (ADR-0047): it starts nothing meanwhile. */
        AT_THE_MEETING("the agent is at the director's meeting");

        private final String why;

        Reason(String why) {
            this.why = why;
        }

        /** The reason as the journal records it. */
        String why() {
            return why;
        }
    }
}
