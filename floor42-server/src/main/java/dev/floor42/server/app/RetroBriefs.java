package dev.floor42.server.app;

import static dev.floor42.server.app.BriefSections.clip;

import dev.floor42.domain.Action;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.Company;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Question;
import dev.floor42.domain.Retro;
import dev.floor42.domain.RetrosFacade;
import dev.floor42.domain.Ticket;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The end-of-mission retrospective (ADR-0020): the facts Floor42 counted, each participant's turn, the chair's
 * conclusion. Nothing in the facts is an opinion; the participants bring those.
 */
final class RetroBriefs {

    private static final int MAX_DENIALS = 6;

    private RetroBriefs() {}

    /** The mission's facts from the journal, counted, with costs marked as Claude Code's estimates. */
    static String facts(Company c, Mission m) {
        return header(m) + tickets(c, m) + sessions(c, m) + actions(c, m) + questions(c, m) + checks(c, m);
    }

    private static String header(Mission m) {
        String outcome = m.outcome() != null && !m.outcome().isBlank() ? " (" + clip(m.outcome(), 300) + ")" : "";
        return "Mission " + m.id() + " \"" + m.title() + "\": " + m.status() + outcome + "\nObjective: "
                + clip(m.objective(), 600) + "\n\nTickets:\n";
    }

    private static String tickets(Company c, Mission m) {
        StringBuilder s = new StringBuilder();
        for (Ticket t : c.tickets().of(m.id())) {
            s.append("- ")
                    .append(t.id())
                    .append(" \"")
                    .append(t.title())
                    .append("\": ")
                    .append(t.status());
            if (t.assignee() != null) s.append(", produced by ").append(t.assignee());
            if (!t.reviews().isEmpty()) {
                s.append("; reviews: ")
                        .append(t.reviews().stream()
                                .map(r -> r.verdict() + " by " + r.reviewer())
                                .collect(Collectors.joining(", ")));
            }
            if (!t.consultations().isEmpty())
                s.append("; help asked ").append(t.consultations().size()).append("x");
            if (!t.directives().isEmpty())
                s.append("; director directives: ").append(t.directives().size());
            s.append("\n");
        }
        return s.toString();
    }

    private static String sessions(Company c, Mission m) {
        List<AgentSession> sessions = c.sessions().of(m.id());
        StringBuilder s = new StringBuilder("\nSessions recorded by Floor42: ")
                .append(sessions.size())
                .append(", ")
                .append(failed(sessions))
                .append(
                        m.origin() == dev.floor42.domain.Origin.REAL
                                ? " failed; cost estimated by Claude Code: "
                                        + usd(c.verification().spentUsdEstimated(m.id())) + " USD" + bounds(c, m)
                                        + ".\n"
                                : " failed; cost: none, simulated (no model was called).\n");
        boolean real = m.origin() == dev.floor42.domain.Origin.REAL;
        sessions.stream()
                .map(AgentSession::agent)
                .distinct()
                .forEach(a -> s.append(real ? sessionsOf(sessions, a) : simulatedSessionsOf(sessions, a)));
        return s.toString();
    }

    private static String sessionsOf(List<AgentSession> sessions, AgentId agent) {
        List<AgentSession> mine =
                sessions.stream().filter(x -> x.agent().equals(agent)).toList();
        double cost = mine.stream()
                .map(AgentSession::costUsdEstimated)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        return "- " + agent + ": " + mine.size() + " session(s), " + failed(mine) + " failed, " + usd(cost)
                + " USD estimated" + bounds(mine) + "\n";
    }

    /**
     * The mission's one upper bound, as its budget counts it (Verification): the sessions with no reported cost and the
     * meeting turns charged their budget, each once; said apart, never as an estimate.
     */
    private static String bounds(Company c, Mission m) {
        int bounded = c.verification().sessionsBounded(m.id());
        if (bounded == 0) return "";
        return " + up to " + usd(c.verification().spentUsdBound(m.id())) + " USD for " + bounded
                + " session(s) with no reported cost";
    }

    /** The sessions that reported no cost count for their cap: said apart, as an upper bound, never as an estimate. */
    private static String bounds(List<AgentSession> sessions) {
        List<Double> caps = sessions.stream()
                .filter(x -> x.costUsdEstimated() == null && x.costUsdBound() != null)
                .map(AgentSession::costUsdBound)
                .toList();
        if (caps.isEmpty()) return "";
        double bound = caps.stream().mapToDouble(Double::doubleValue).sum();
        return " + up to " + usd(bound) + " USD for " + caps.size() + " session(s) with no reported cost";
    }

    /** A simulated mission's sessions paid nothing: no amount at all, whatever a replayed recording reported. */
    private static String simulatedSessionsOf(List<AgentSession> sessions, AgentId agent) {
        List<AgentSession> mine =
                sessions.stream().filter(x -> x.agent().equals(agent)).toList();
        return "- " + agent + ": " + mine.size() + " session(s), " + failed(mine) + " failed, simulated\n";
    }

    private static long failed(List<AgentSession> sessions) {
        return sessions.stream().filter(x -> Boolean.TRUE.equals(x.failed())).count();
    }

    private static String usd(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private static String actions(Company c, Mission m) {
        List<Action> actions = c.actions().of(m.id());
        List<Action> refused = actions.stream().filter(Action::refused).toList();
        StringBuilder s = new StringBuilder("\nTool requests: ")
                .append(actions.size())
                .append(", ")
                .append(refused.size())
                .append(" refused.\n");
        refused.stream()
                .limit(MAX_DENIALS)
                .forEach(a -> s.append("- refused: ")
                        .append(a.agent())
                        .append(" ")
                        .append(a.tool())
                        .append(" ")
                        .append(clip(a.summary(), 120))
                        .append(" (")
                        .append(a.denialReason() != null ? a.denialReason() : clip(a.result(), 60))
                        .append(")\n"));
        return s.toString();
    }

    private static String questions(Company c, Mission m) {
        List<Question> questions = c.desk().of(m.id());
        String kinds = questions.stream()
                .collect(Collectors.groupingBy(q -> q.kind().name(), TreeMap::new, Collectors.counting()))
                .entrySet()
                .stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        return "Questions to the director: " + questions.size() + (kinds.isEmpty() ? "" : " (" + kinds + ")") + ".\n";
    }

    private static String checks(Company c, Mission m) {
        List<CheckRun> checks = c.verification().checkRuns(m.id());
        if (checks.isEmpty()) return "";
        return "Checks run by Floor42: " + checks.size() + ", "
                + checks.stream().filter(x -> !x.passed()).count() + " failed.\n";
    }

    /** A participant's turn: what went well and what did not, from the facts, in a few lines. */
    static String participant(Mission m, Retro r, Agent agent) {
        return BriefTemplate.named("retro-participant")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with(
                        "participants",
                        r.participants().stream().map(Object::toString).collect(Collectors.joining(", ")))
                .with("facts", r.facts())
                .render();
    }

    /** The chair's conclusion: what the team keeps, what it changes, and at most a few lessons for the director. */
    static String chair(Company c, Mission m, Retro r, Agent agent) {
        return BriefTemplate.named("retro-chair")
                .with("name", agent.displayName())
                .with("role", agent.role())
                .with("mission", m.id())
                .with("title", m.title())
                .with("facts", r.facts())
                .with("said", said(r))
                .with("inForce", inForce(c))
                .with("maxLessons", RetrosFacade.MAX_LESSONS_PER_RETRO)
                .render();
    }

    private static String said(Retro r) {
        StringBuilder said = new StringBuilder();
        for (Retro.Contribution x : r.contributions()) {
            said.append("\n=== ").append(x.agent()).append(" ===\n");
            said.append(x.skipped() != null ? "(did not speak: " + x.skipped() + ")" : clip(x.text(), 3_000));
            said.append("\n");
        }
        return said.toString();
    }

    /** Lessons the director already accepted: the chair does not propose them again. */
    private static String inForce(Company c) {
        List<Lesson> accepted = c.retros().lessons().stream()
                .filter(l -> l.status() == Lesson.Status.ACCEPTED)
                .toList();
        if (accepted.isEmpty()) return "";
        return "Lessons already in force (do not propose them again):\n"
                + accepted.stream().map(l -> "- " + l.text()).collect(Collectors.joining("\n"))
                + "\n\n";
    }
}
