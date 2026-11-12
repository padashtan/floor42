package dev.floor42.server.api;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PlanUsage;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Read models of the director's usage page: the plan's windows, the night, and what agent sessions cost. */
public final class UsageViews {

    private UsageViews() {}

    /** One usage window of the plan, as last reported by Claude Code. Stale once the window has reset. */
    public record UsageWindowView(
            String window,
            String label,
            double utilization,
            String resetsAt,
            String observedAt,
            boolean current,
            String status,
            Double expectedIncrease) {
        static UsageWindowView of(Company c, PlanUsage usage, PlanUsage.Reading r, Instant now) {
            return new UsageWindowView(
                    r.window(),
                    PlanUsage.label(r.window()),
                    r.utilization(),
                    r.resetsAt().toString(),
                    r.observedAt().toString(),
                    r.current(now),
                    r.status(),
                    usage.expectedIncrease(r.window(), null, c.sessions()::modelOf)
                            .orElse(null));
        }
    }

    /**
     * The office closes for the night when the plan's quota is spent: a usage window measured at its limit, or the
     * director chose to wait for a window's reset. {@code resumesAt} is the reset time reported by Claude Code.
     *
     * @param code why, for the screen to say it in its language: {@code quotaUsed} or {@code waitingReset}
     */
    public record NightView(String window, String label, String resumesAt, String reason, String code) {

        static NightView of(Company c, double threshold, Instant now) {
            // Nobody goes home in the middle of a session: the night starts once every session has ended.
            boolean working = c.missions().all().stream()
                    .flatMap(m -> c.sessions().of(m.id()).stream())
                    .anyMatch(AgentSession::isOpen);
            if (working) return null;
            Optional<NightView> spent = quotaUsed(c, now);
            if (spent.isPresent()) return spent.get();
            if (!waitingForReset(c)) return null;
            return c.verification()
                    .planLimitRisk(null, threshold, now)
                    .map(risk -> new NightView(
                            risk.window(),
                            PlanUsage.label(risk.window()),
                            risk.resetsAt().toString(),
                            "waiting for the " + PlanUsage.label(risk.window()) + " quota to reset, as you decided",
                            "waitingReset"))
                    .orElse(null);
        }

        private static Optional<NightView> quotaUsed(Company c, Instant now) {
            return c.verification().planUsage().latest().values().stream()
                    .filter(r -> r.current(now) && (r.blocked() || r.utilization() >= 1.0))
                    .findFirst()
                    .map(r -> new NightView(
                            r.window(),
                            PlanUsage.label(r.window()),
                            r.resetsAt().toString(),
                            "the " + PlanUsage.label(r.window()) + " quota is used up",
                            "quotaUsed"));
        }

        /** The director decided to wait for the reset, and no session started since. */
        private static boolean waitingForReset(Company c) {
            return c.missions().all().stream()
                    .filter(m -> m.origin() == Origin.REAL && !m.status().isTerminal())
                    .anyMatch(m -> {
                        Optional<Question> last = c.desk().of(m.id()).stream()
                                .filter(q -> q.kind() == QuestionKind.PLAN_LIMIT)
                                .reduce((a, b) -> b);
                        if (last.isEmpty() || !last.get().answered(OptionCode.WAIT_FOR_RESET)) return false;
                        Instant asked = last.get().askedAt();
                        return c.sessions().of(m.id()).stream()
                                .noneMatch(s -> s.startedAt().isAfter(asked));
                    });
        }
    }

    /**
     * Estimated cost and provided tokens of agent sessions, per day (UTC) or per mission.
     *
     * @param archived a mission the director put away (ADR-0054): its sessions stay counted; false for a day
     */
    public record UsageLine(
            String key,
            String title,
            int sessions,
            double costUsdEstimated,
            long inputTokens,
            long outputTokens,
            boolean archived) {
        static UsageLine of(String key, String title, List<AgentSession> sessions, boolean archived) {
            return new UsageLine(
                    key,
                    title,
                    sessions.size(),
                    sessions.stream()
                            .map(AgentSession::costUsdEstimated)
                            .filter(Objects::nonNull)
                            .mapToDouble(Double::doubleValue)
                            .sum(),
                    Scorecard.sum(sessions, AgentSession::inputTokensProvided),
                    Scorecard.sum(sessions, AgentSession::outputTokensProvided),
                    archived);
        }
    }

    /**
     * The director's usage page: the plan's windows (measured by Claude Code), what a session adds to them (estimated by
     * Floor42), and what agent sessions cost (estimated by Claude Code) per day and per mission.
     */
    public record UsageView(
            List<UsageWindowView> windows,
            double threshold,
            List<UsageLine> days,
            List<UsageLine> missions,
            String accountUsageUrl,
            NightView night) {
        public UsageView {
            days = days == null ? null : Collections.unmodifiableList(new ArrayList<>(days));
            missions = missions == null ? null : Collections.unmodifiableList(new ArrayList<>(missions));
            windows = windows == null ? null : Collections.unmodifiableList(new ArrayList<>(windows));
        }

        static UsageView of(Company c, double threshold, Instant now) {
            PlanUsage usage = c.verification().planUsage();
            List<UsageWindowView> windows = usage.latest().values().stream()
                    .map(r -> UsageWindowView.of(c, usage, r, now))
                    .toList();
            // Real missions only: a simulated session's cost is a recording's, not today's spending.
            List<AgentSession> sessions = c.missions().all().stream()
                    .filter(m -> m.origin() == Origin.REAL)
                    .flatMap(m -> c.sessions().of(m.id()).stream())
                    .toList();
            return new UsageView(
                    windows,
                    threshold,
                    days(sessions),
                    missions(c, sessions),
                    "https://claude.ai/settings/usage",
                    NightView.of(c, threshold, now));
        }

        /** Newest day first. */
        private static List<UsageLine> days(List<AgentSession> sessions) {
            Map<String, List<AgentSession>> byDay = new TreeMap<>(Comparator.reverseOrder());
            for (AgentSession s : sessions) {
                byDay.computeIfAbsent(s.startedAt().toString().substring(0, 10), k -> new ArrayList<>())
                        .add(s);
            }
            return byDay.entrySet().stream()
                    .map(e -> UsageLine.of(e.getKey(), null, e.getValue(), false))
                    .toList();
        }

        /** The mission whose first session came last, first. */
        private static List<UsageLine> missions(Company c, List<AgentSession> sessions) {
            Map<String, List<AgentSession>> byMission = new LinkedHashMap<>();
            for (AgentSession s : sessions) {
                byMission
                        .computeIfAbsent(s.mission().value(), k -> new ArrayList<>())
                        .add(s);
            }
            List<UsageLine> missions = new ArrayList<>();
            byMission.forEach((mission, list) -> {
                Optional<Mission> m = c.missions().find(new MissionId(mission));
                missions.addFirst(UsageLine.of(
                        mission,
                        m.map(Mission::title).orElse(null),
                        list,
                        m.map(Mission::archived).orElse(false)));
            });
            return missions;
        }
    }
}
