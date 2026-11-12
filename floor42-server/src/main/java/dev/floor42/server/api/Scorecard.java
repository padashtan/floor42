package dev.floor42.server.api;

import dev.floor42.domain.Action;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.Committee;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.Costs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/** A mission's results (spec sections 15 and 16), one line per fact, each with how it was obtained. */
public final class Scorecard {

    private static final String MEASURED = "measured by Floor42";
    private static final String UNAVAILABLE = "unavailable";

    private Scorecard() {}

    /**
     * One line of a mission's results (spec sections 15 and 16). {@code basis} says how the value was obtained:
     * measured by Floor42, provided by the runtime, estimated by the runtime, reported by an agent, or unavailable.
     */
    public record ScoreLine(String label, String value, String basis, String code, Map<String, String> params) {
        public ScoreLine {
            params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        ScoreLine(String label, String value, String basis) {
            this(label, value, basis, null, (Map<String, String>) null);
        }

        /** A value the screen can write in its language from a code and values (ADR-0021, lot 3). */
        ScoreLine(String label, String value, String basis, String code, String... keysAndValues) {
            this(
                    label,
                    value,
                    basis,
                    code,
                    code == null ? null : Phrase.of(code, keysAndValues).params());
        }
    }

    static List<ScoreLine> of(Company c, Mission m) {
        List<ScoreLine> lines = new ArrayList<>();
        List<AgentSession> sessions = c.sessions().of(m.id());
        lines.add(checks(m, c.verification().checkRuns(m.id())));
        List<Ticket> tickets = c.tickets().of(m.id());
        long done =
                tickets.stream().filter(t -> t.status() == TicketStatus.DONE).count();
        lines.add(new ScoreLine("Tickets done", done + " / " + tickets.size(), MEASURED));
        lines.add(review(tickets));
        c.committees().latest(m.id()).ifPresent(k -> lines.add(committee(k)));
        lines.add(cost(c, m, sessions));
        lines.add(tokens(sessions));
        // A turn of speech at the director's meeting is spend, not agent time (ADR-0049).
        lines.add(agentTime(dev.floor42.server.app.TurnSessions.work(c, sessions)));
        lines.add(governance(c, m));
        int interventions = c.workshop().interventions(m.id()).size();
        lines.add(new ScoreLine(
                "Your interventions",
                interventions + " in the workshop",
                MEASURED,
                "interventions",
                "count",
                Integer.toString(interventions)));
        long incidents = c.actions().incidents().stream()
                .filter(i -> i.mission().equals(m.id()))
                .count();
        lines.add(new ScoreLine("Incidents", String.valueOf(incidents), MEASURED));
        return lines;
    }

    private static ScoreLine checks(Mission m, List<CheckRun> runs) {
        if (m.checkCommand() == null) return new ScoreLine("Checks", "none configured", UNAVAILABLE, "checks.none");
        if (runs.isEmpty()) return new ScoreLine("Checks", "not run yet", UNAVAILABLE, "checks.notRun");
        CheckRun last = runs.getLast();
        String outcome =
                last.passed() ? "passed" : last.timedOut() ? "timed out" : "failed (exit " + last.exitCode() + ")";
        return new ScoreLine(
                "Checks",
                outcome + " · " + runs.size() + " run(s)",
                MEASURED,
                last.passed() ? "checks.passed" : last.timedOut() ? "checks.timedOut" : "checks.failed",
                "exit",
                String.valueOf(last.exitCode()),
                "runs",
                Integer.toString(runs.size()));
    }

    private static ScoreLine review(List<Ticket> tickets) {
        String verdicts = tickets.stream()
                .filter(t -> !t.reviews().isEmpty())
                .map(t -> t.id() + " " + spoken(t.reviews().getLast().verdict().name()))
                .collect(Collectors.joining(", "));
        return new ScoreLine(
                "Independent review",
                verdicts.isEmpty() ? "none yet" : verdicts,
                verdicts.isEmpty() ? UNAVAILABLE : "reported by the reviewer agent",
                verdicts.isEmpty() ? "review.none" : null);
    }

    private static ScoreLine committee(Committee k) {
        String stage = spoken(k.stage().name());
        int members = k.memberIds().size();
        int contributions = k.contributions().size();
        int objections = k.standingCriticalObjections().size();
        return new ScoreLine(
                "Committee",
                stage + " · " + members + " members · " + contributions + " contributions · " + objections
                        + " critical objection(s) standing",
                "measured by Floor42; contents reported by the member agents",
                "committee",
                "stage",
                stage,
                "members",
                Integer.toString(members),
                "contributions",
                Integer.toString(contributions),
                "objections",
                Integer.toString(objections));
    }

    private static ScoreLine cost(Company c, Mission m, List<AgentSession> sessions) {
        if (m.origin() != Origin.REAL) {
            // Nothing was paid: a replay's recorded estimate belongs to the day it was recorded.
            return new ScoreLine("Cost", "0 USD (simulated: no model was called)", Costs.SIMULATED, "cost.simulated");
        }
        int bounded = c.verification().sessionsBounded(m.id());
        if (bounded > 0) return bounded(c, m, bounded);
        boolean anyCost = sessions.stream().anyMatch(s -> s.costUsdEstimated() != null);
        double spent = c.verification().spentUsdEstimated(m.id());
        Double budget = m.budgetUsd();
        String of = budget == null ? "" : String.format(Locale.ROOT, " of %.4f USD", budget);
        String none = sessions.isEmpty() ? "no session yet" : "no cost reported";
        return new ScoreLine(
                "Cost",
                anyCost ? String.format(Locale.ROOT, "%.4f USD", spent) + of : none,
                anyCost ? "estimated by Claude Code" : UNAVAILABLE,
                !anyCost
                        ? (sessions.isEmpty() ? "cost.none" : "cost.unreported")
                        : budget == null ? "cost.usd" : "cost.budget",
                "usd",
                String.format(Locale.ROOT, "%.4f", spent),
                "budget",
                budget == null ? "" : String.format(Locale.ROOT, "%.4f", budget));
    }

    /** Some sessions reported no cost: their caps are shown apart, as an upper bound, never added to the estimate. */
    private static ScoreLine bounded(Company c, Mission m, int bounded) {
        String usd = String.format(Locale.ROOT, "%.4f", c.verification().spentUsdEstimated(m.id()));
        String bound = String.format(Locale.ROOT, "%.4f", c.verification().spentUsdBound(m.id()));
        Double budget = m.budgetUsd();
        String of = budget == null ? "" : String.format(Locale.ROOT, ", of a %.4f USD budget", budget);
        return new ScoreLine(
                "Cost",
                "estimated " + usd + " USD + up to " + bound + " USD for " + bounded
                        + " session(s) with no reported cost" + of,
                "estimated by Claude Code, plus the caps of the sessions that reported no cost (an upper bound)",
                budget == null ? "cost.bounded" : "cost.boundedBudget",
                "usd",
                usd,
                "bound",
                bound,
                "bounded",
                Integer.toString(bounded),
                "budget",
                budget == null ? "" : String.format(Locale.ROOT, "%.4f", budget));
    }

    private static ScoreLine tokens(List<AgentSession> sessions) {
        long in = sum(sessions, AgentSession::inputTokensProvided);
        long out = sum(sessions, AgentSession::outputTokensProvided);
        return new ScoreLine(
                "Tokens",
                in + " in · " + out + " out · " + sessions.size() + " session(s)",
                "provided by Claude Code",
                "tokens",
                "in",
                Long.toString(in),
                "out",
                Long.toString(out),
                "sessions",
                Integer.toString(sessions.size()));
    }

    private static ScoreLine agentTime(List<AgentSession> sessions) {
        ToLongFunction<AgentSession> ms =
                s -> Duration.between(s.startedAt(), s.endedAt()).toMillis();
        long total =
                sessions.stream().filter(s -> s.endedAt() != null).mapToLong(ms).sum();
        return new ScoreLine("Agent time", String.format(Locale.ROOT, "%.1f s", total / 1000.0), MEASURED);
    }

    private static ScoreLine governance(Company c, Mission m) {
        List<Action> actions = c.actions().of(m.id());
        long denied = actions.stream().filter(Action::refused).count();
        long asked = c.desk().of(m.id()).stream()
                .filter(q -> q.kind() == QuestionKind.ACTION_APPROVAL)
                .count();
        return new ScoreLine(
                "Governance",
                actions.size() + " tool calls · " + asked + " asked you · " + denied + " refused",
                MEASURED,
                "governance",
                "calls",
                Integer.toString(actions.size()),
                "asked",
                Long.toString(asked),
                "refused",
                Long.toString(denied));
    }

    /** Sums a count the runtime may not have reported: missing values count for nothing. */
    static long sum(List<AgentSession> sessions, Function<AgentSession, Long> count) {
        return sessions.stream()
                .map(count)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    private static String spoken(String constant) {
        return constant.toLowerCase().replace('_', ' ');
    }
}
