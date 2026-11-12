package dev.floor42.server.app;

import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.WorkRole;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** What the staffing planner is given, and Floor42's own estimate, measured from the journal. */
final class StaffingFacts {

    private StaffingFacts() {}

    /** The mission's tickets and staff, and the averages measured on its done tickets. */
    private record Measured(
            List<Ticket> left,
            List<Ticket> done,
            long ready,
            List<Agent> developers,
            List<Agent> reviewers,
            double workMinutes,
            double elapsedMinutes,
            double cost,
            boolean real) {}

    static Staffing.Facts facts(Company c, MissionId m, int parallel) {
        Measured f = measure(c, m);
        return new Staffing.Facts(text(f, parallel), estimate(f, parallel));
    }

    private static Measured measure(Company c, MissionId m) {
        List<Ticket> tickets = c.tickets().of(m).stream()
                .filter(t -> !c.staff().isStaffingTicket(t.id()))
                .toList();
        List<Ticket> done = tickets.stream()
                .filter(t -> t.status() == TicketStatus.DONE && t.startedAt() != null && t.endedAt() != null)
                .toList();
        List<Ticket> left = tickets.stream()
                .filter(t -> t.status() != TicketStatus.DONE && t.status() != TicketStatus.CANCELLED)
                .toList();
        long ready = ready(c, left);
        List<AgentSession> sessions = c.sessions().of(m);
        return new Measured(
                left,
                done,
                ready,
                Staff.active(c, WorkRole.DEVELOPER),
                Staff.active(c, WorkRole.REVIEWER),
                workMinutes(done, sessions),
                done.stream()
                                .mapToLong(t -> Duration.between(t.startedAt(), t.endedAt())
                                        .toMillis())
                                .average()
                                .orElse(0)
                        / 60_000.0,
                averageCost(done, sessions),
                // A simulated mission called no model: no amount at all, whatever a replayed recording reported.
                c.missions().find(m).map(x -> x.origin() == Origin.REAL).orElse(true));
    }

    /** The tickets to do whose dependencies are all done. */
    private static long ready(Company c, List<Ticket> left) {
        return left.stream()
                .filter(t -> t.status() == TicketStatus.TODO)
                .filter(t -> t.dependsOn().stream()
                        .allMatch(d -> c.tickets()
                                .find(d)
                                .map(x -> x.status() == TicketStatus.DONE)
                                .orElse(false)))
                .count();
    }

    private static double workMinutes(List<Ticket> done, List<AgentSession> sessions) {
        return done.stream()
                        .mapToLong(t -> sessions.stream()
                                .filter(s -> s.ticket().equals(t.id()))
                                .mapToLong(s -> s.workTime().toMillis())
                                .sum())
                        .average()
                        .orElse(0)
                / 60_000.0;
    }

    private static double averageCost(List<Ticket> done, List<AgentSession> sessions) {
        return done.stream()
                .mapToDouble(t -> sessions.stream()
                        .filter(s -> s.ticket().equals(t.id()) && s.costUsdEstimated() != null)
                        .mapToDouble(AgentSession::costUsdEstimated)
                        .sum())
                .average()
                .orElse(0);
    }

    private static String text(Measured f, int parallel) {
        StringBuilder text = new StringBuilder();
        text.append("- Tickets left: ")
                .append(f.left().size())
                .append(" (")
                .append(count(f.left(), TicketStatus.PROPOSED))
                .append(" proposed, ")
                .append(count(f.left(), TicketStatus.TODO))
                .append(" to do of which ")
                .append(f.ready())
                .append(" ready to start now, ")
                .append(count(f.left(), TicketStatus.IN_PROGRESS))
                .append(" in progress, ")
                .append(count(f.left(), TicketStatus.IN_REVIEW))
                .append(" in review).\n");
        for (Ticket t : f.left()) {
            text.append("  - ")
                    .append(t.id())
                    .append(" [")
                    .append(t.status())
                    .append("] ")
                    .append(t.title());
            if (!t.dependsOn().isEmpty())
                text.append(" (after ").append(t.dependsOn()).append(")");
            text.append("\n");
        }
        staff(text, f, parallel);
        return text.toString();
    }

    private static void staff(StringBuilder text, Measured f, int parallel) {
        text.append("- Developers: ")
                .append(f.developers().size())
                .append(" (")
                .append(f.developers().stream()
                        .map(a -> a.displayName() + ", " + a.role())
                        .toList())
                .append(").\n");
        text.append("- Reviewers: ").append(f.reviewers().size()).append(". Every ticket is reviewed once done.\n");
        text.append("- Parallel sessions allowed per mission: ")
                .append(parallel)
                .append(" (a company setting, 1 to 4).\n");
        if (f.done().isEmpty()) {
            text.append("- No ticket of this mission is done yet: no measured average per ticket.\n");
            return;
        }
        text.append(String.format(
                Locale.ROOT,
                "- Per done ticket (%d): %.0f min from start to done and %.0f min of agent sessions (measured by"
                        + " Floor42), %s.\n",
                f.done().size(),
                f.elapsedMinutes(),
                f.workMinutes(),
                perTicket(f)));
    }

    private static String perTicket(Measured f) {
        return f.real()
                ? String.format(Locale.ROOT, "%.2f USD (estimated by Claude Code)", f.cost())
                : "no cost (simulated: no model was called)";
    }

    private static String estimate(Measured f, int parallel) {
        if (f.done().isEmpty() || f.left().isEmpty()) {
            return "Floor42 has no measured average on this mission yet, so it gives no time estimate.";
        }
        int now = Math.min(Math.max(1, f.developers().size()), parallel);
        // One more developer, and the parallel sessions raised to match (4 at most).
        int more = Math.min(4, now + 1);
        double serial = f.left().size() * f.elapsedMinutes();
        return String.format(
                Locale.ROOT,
                "Estimated by Floor42 from this mission's measured averages: %d ticket(s) left, about %.0f min each"
                        + " from start to done. At %d at a time, about %.0f min; with one more developer working"
                        + " in parallel, about %.0f min. The work costs about the same in tokens (per ticket: %s),"
                        + " but it is spent sooner: the plan's usage windows fill"
                        + " faster, and the reviewers get more to review at once.",
                f.left().size(),
                f.elapsedMinutes(),
                now,
                serial / now,
                serial / more,
                perTicket(f));
    }

    private static long count(List<Ticket> tickets, TicketStatus status) {
        return tickets.stream().filter(t -> t.status() == status).count();
    }
}
