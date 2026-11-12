package dev.floor42.server.app;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.ChargedTurn;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the sessions cost, read from the journal on each call: per mission, per agent, per ticket, and today, this week
 * and this month across real missions. A projection, never a source of truth: nothing here is recorded. Every amount is
 * Claude Code's own estimate ({@code total_cost_usd}), never a bill; the token counts are what Claude Code reported;
 * the sessions are counted by Floor42. Each line says so, and says how many sessions reported no cost, so a sum that
 * misses some is never shown as complete. A simulated mission called no model: its lines say {@value #SIMULATED} and 0,
 * whatever the recording it replays once cost.
 */
@ApplicationScoped
public class Costs {

    public static final String ESTIMATED = "estimated by Claude Code";
    public static final String PROVIDED = "provided by Claude Code";
    public static final String MEASURED = "measured by Floor42";
    public static final String UNAVAILABLE = "unavailable";
    public static final String SIMULATED = "simulated: no model was called";

    /** How a mission's sessions become a figure: estimated for a real mission, zero for a simulated one. */
    @FunctionalInterface
    interface Liner {
        CostLine of(String key, String name, Collection<AgentSession> sessions, Collection<ChargedTurn> charges);
    }

    @Inject
    OfficeService office;

    /**
     * One figure and its bases.
     *
     * @param key what it counts: a mission, an agent, a ticket, or a period (today, week, month)
     * @param name its title or name, for the screen
     * @param usdEstimated the sum of the costs Claude Code reported; sessions without one add nothing
     * @param costBasis how the amount was obtained, {@value #ESTIMATED}, {@value #UNAVAILABLE} when no session reported
     *     one, or {@value #SIMULATED}
     * @param tokensBasis {@value #PROVIDED}, or {@value #UNAVAILABLE} when no session reported tokens
     * @param sessions sessions counted, ended or not
     * @param sessionsWithoutCost ended sessions that reported no cost: when above zero the amount is a lower bound
     * @param usdBound the caps of those of them that count for their cap in the budget, and the budgets of the meeting
     *     turns charged with no session of their own to count them (in the period of their turn, the row of their
     *     speaker and of the meeting's ticket): an upper bound on what was spent, never added to {@code usdEstimated}; null
     *     when nothing is bounded (0 would read as a real figure)
     * @param sessionsOpen sessions still running: their cost is reported when they end
     * @param sessionsBasis always {@value #MEASURED}
     */
    public record CostLine(
            String key,
            String name,
            double usdEstimated,
            String costBasis,
            long inputTokens,
            long outputTokens,
            String tokensBasis,
            int sessions,
            int sessionsWithoutCost,
            Double usdBound,
            int sessionsOpen,
            String sessionsBasis) {}

    /**
     * A mission's total, with its cap.
     *
     * @param budgetUsd the cap, compared with the estimates plus the bounds of the sessions that reported no cost; null
     *     for none
     * @param capDecided whether the cap was decided (a budget, or "no cap" chosen, both journaled)
     * @param archived the director put the mission away (ADR-0054): what it spent stays counted, marked
     */
    public record MissionCost(
            String mission,
            String title,
            String origin,
            String status,
            Double budgetUsd,
            boolean capDecided,
            CostLine total,
            boolean archived) {}

    /** A mission's costs per agent and per ticket, most expensive first. */
    public record MissionCosts(MissionCost mission, List<CostLine> agents, List<CostLine> tickets) {
        public MissionCosts {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
            tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
        }
    }

    /**
     * Spending across missions. The periods count real missions only (a simulated mission spends nothing), each session
     * in the period it ended in, or started in while it runs, in {@code zone}; the week starts on Monday.
     *
     * @param at when this was read
     */
    public record CostsOverview(
            String zone, String at, CostLine today, CostLine week, CostLine month, List<MissionCost> missions) {
        public CostsOverview {
            missions = missions == null ? null : Collections.unmodifiableList(new ArrayList<>(missions));
        }
    }

    public CostsOverview overview(ZoneId zone, Instant now) {
        return office.query(c -> overviewOf(c, zone, now));
    }

    /** The overview read from one state of the company. */
    static CostsOverview overviewOf(Company c, ZoneId zone, Instant now) {
        LocalDate day = now.atZone(zone).toLocalDate();
        Instant today = day.atStartOfDay(zone).toInstant();
        Instant week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone)
                .toInstant();
        Instant month = day.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        List<Mission> realMissions = c.missions().all().stream()
                .filter(m -> m.origin() == Origin.REAL)
                .toList();
        List<AgentSession> real = realMissions.stream()
                .flatMap(m -> c.sessions().of(m.id()).stream())
                .toList();
        List<ChargedTurn> charged = realMissions.stream()
                .flatMap(m -> c.verification().chargedTurns(m.id()).stream())
                .toList();
        return new CostsOverview(
                zone.getId(),
                now.toString(),
                line("today", null, since(real, today), chargedSince(charged, today)),
                line("week", null, since(real, week), chargedSince(charged, week)),
                line("month", null, since(real, month), chargedSince(charged, month)),
                c.missions().all().stream()
                        .sorted(Comparator.comparing(Mission::createdAt).reversed())
                        .map(m -> missionCost(c, m))
                        .toList());
    }

    public Optional<MissionCosts> of(MissionId id) {
        return office.query(c -> c.missions().find(id).map(m -> costsOf(c, m)));
    }

    /** A mission's costs per agent and per ticket, read from one state of the company. */
    static MissionCosts costsOf(Company c, Mission m) {
        MissionId id = m.id();
        List<AgentSession> sessions = c.sessions().of(id);
        List<ChargedTurn> charged = c.verification().chargedTurns(id);
        Liner liner = liner(m);
        return new MissionCosts(
                missionCost(c, m),
                grouped(
                        liner,
                        sessions,
                        charged,
                        s -> s.agent().value(),
                        t -> t.attendee().value(),
                        key -> c.staff()
                                .find(new dev.floor42.domain.AgentId(key))
                                .map(dev.floor42.domain.Agent::displayName)
                                .orElse(key)),
                grouped(
                        liner,
                        sessions,
                        charged,
                        s -> s.ticket().value(),
                        t -> t.ticket().value(),
                        key -> c.tickets()
                                .find(new dev.floor42.domain.TicketId(key))
                                .map(dev.floor42.domain.Ticket::title)
                                .orElse(key)));
    }

    static MissionCost missionCost(Company c, Mission m) {
        // The bound as the budget counts it (Verification): a meeting turn charged with no session of its own too.
        CostLine total = liner(m).of(
                        m.id().value(),
                        m.title(),
                        c.sessions().of(m.id()),
                        c.verification().chargedTurns(m.id()));
        return new MissionCost(
                m.id().value(),
                m.title(),
                m.origin().name(),
                m.status().name(),
                m.budgetUsd(),
                m.capDecided(),
                total,
                m.archived());
    }

    private static Liner liner(Mission m) {
        return m.origin() == Origin.REAL ? Costs::line : Costs::simulated;
    }

    /** A charged turn belongs to the period of its turn. */
    private static List<ChargedTurn> chargedSince(List<ChargedTurn> charged, Instant from) {
        return charged.stream().filter(t -> !t.at().isBefore(from)).toList();
    }

    private static List<AgentSession> since(List<AgentSession> sessions, Instant from) {
        return sessions.stream()
                .filter(s -> !(s.endedAt() == null ? s.startedAt() : s.endedAt()).isBefore(from))
                .toList();
    }

    private static List<CostLine> grouped(
            Liner liner,
            List<AgentSession> sessions,
            List<ChargedTurn> charged,
            Function<AgentSession, String> key,
            Function<ChargedTurn, String> chargeKey,
            Function<String, String> name) {
        Map<String, List<AgentSession>> groups = new LinkedHashMap<>();
        for (AgentSession s : sessions) {
            groups.computeIfAbsent(key.apply(s), k -> new java.util.ArrayList<>())
                    .add(s);
        }
        // A charged turn goes to its speaker's row and its meeting ticket's row, made for it when no session has one.
        Map<String, List<ChargedTurn>> charges = new LinkedHashMap<>();
        for (ChargedTurn t : charged) {
            charges.computeIfAbsent(chargeKey.apply(t), k -> new java.util.ArrayList<>())
                    .add(t);
            groups.putIfAbsent(chargeKey.apply(t), List.of());
        }
        return groups.entrySet().stream()
                .map(e -> liner.of(
                        e.getKey(), name.apply(e.getKey()), e.getValue(), charges.getOrDefault(e.getKey(), List.of())))
                .sorted(Comparator.comparingDouble(CostLine::usdEstimated).reversed())
                .toList();
    }

    /** Nothing was paid: the sessions are counted, their amount is 0 and no token was spent. */
    static CostLine simulated(
            String key, String name, Collection<AgentSession> sessions, Collection<ChargedTurn> charges) {
        int open = (int) sessions.stream().filter(AgentSession::isOpen).count();
        return new CostLine(key, name, 0, SIMULATED, 0, 0, UNAVAILABLE, sessions.size(), 0, null, open, MEASURED);
    }

    static CostLine line(String key, String name, Collection<AgentSession> sessions, Collection<ChargedTurn> charges) {
        double usd = 0;
        boolean anyTokens = false;
        long in = 0;
        long out = 0;
        int withoutCost = 0;
        int open = 0;
        for (AgentSession s : sessions) {
            if (s.isOpen()) open++;
            else if (s.costUsdEstimated() == null) withoutCost++;
            if (s.costUsdEstimated() != null) usd += s.costUsdEstimated();
            if (s.inputTokensProvided() != null || s.outputTokensProvided() != null) anyTokens = true;
            in += Objects.requireNonNullElse(s.inputTokensProvided(), 0L);
            out += Objects.requireNonNullElse(s.outputTokensProvided(), 0L);
        }
        return new CostLine(
                key,
                name,
                usd,
                sessions.stream().anyMatch(s -> s.costUsdEstimated() != null) ? ESTIMATED : UNAVAILABLE,
                in,
                out,
                anyTokens ? PROVIDED : UNAVAILABLE,
                sessions.size(),
                withoutCost,
                bound(sessions, charges),
                open,
                MEASURED);
    }

    /** The bound of these sessions and charged turns; null when nothing is bounded (0 would read as a figure). */
    static Double bound(Collection<AgentSession> sessions, Collection<ChargedTurn> charges) {
        if (bounded(sessions) == 0 && charges.isEmpty()) return null;
        return bound(sessions) + charges.stream().mapToDouble(ChargedTurn::usd).sum();
    }

    /** How many sessions count for their cap: they reported no cost and have one. */
    static long bounded(Collection<AgentSession> sessions) {
        return sessions.stream()
                .filter(s -> s.costUsdEstimated() == null && s.costUsdBound() != null)
                .count();
    }

    /** The caps of the sessions that reported no cost, counted by the budget: an upper bound, never an estimate. */
    static double bound(Collection<AgentSession> sessions) {
        return sessions.stream()
                .filter(s -> s.costUsdEstimated() == null && s.costUsdBound() != null)
                .mapToDouble(AgentSession::costUsdBound)
                .sum();
    }
}
