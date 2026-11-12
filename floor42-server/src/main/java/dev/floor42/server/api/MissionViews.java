package dev.floor42.server.api;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.api.ActionViews.ActionView;
import dev.floor42.server.api.ActionViews.IncidentView;
import dev.floor42.server.api.CommitteeViews.CommitteeSummaryView;
import dev.floor42.server.api.CommitteeViews.CommitteeView;
import dev.floor42.server.api.QuestionViews.QuestionView;
import dev.floor42.server.api.RetroViews.RetroView;
import dev.floor42.server.api.Scorecard.ScoreLine;
import dev.floor42.server.api.TicketViews.TicketView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read models of missions: the list, one mission with everything the screen shows of it, its sessions and its check
 * runs. Built inside {@code OfficeService.query} from live entities, then immutable. Field names are the API contract
 * consumed by the UI.
 */
public final class MissionViews {

    private MissionViews() {}

    /**
     * @param archived put away by the director once closed (ADR-0054): listed only among the archives
     * @param agents who worked on it, first seen first: the owners of its tickets, their reviewers, anyone with a
     *     session; the scene keeps them at their desks while the mission is in progress
     */
    public record MissionSummary(
            String id,
            String title,
            String status,
            String origin,
            int tickets,
            int ticketsDone,
            int openQuestions,
            boolean archived,
            List<String> agents) {
        public MissionSummary {
            agents = agents == null ? null : Collections.unmodifiableList(new ArrayList<>(agents));
        }

        static MissionSummary of(Company c, Mission m) {
            List<Ticket> tickets = c.tickets().of(m.id());
            return new MissionSummary(
                    m.id().value(),
                    m.title(),
                    m.status().name(),
                    m.origin().name(),
                    tickets.size(),
                    (int) tickets.stream()
                            .filter(t -> t.status() == TicketStatus.DONE)
                            .count(),
                    (int) c.desk().of(m.id()).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .count(),
                    m.archived(),
                    Stream.concat(
                                    c.sessions().of(m.id()).stream()
                                            .map(x -> x.agent().value()),
                                    tickets.stream()
                                            .flatMap(t -> Stream.concat(
                                                    Stream.ofNullable(t.assignee())
                                                            .map(a -> a.value()),
                                                    t.reviews().stream()
                                                            .map(r ->
                                                                    r.reviewer().value()))))
                            .distinct()
                            .toList());
        }
    }

    /** Tokens are provided by the runtime; the cost is its estimate. Null values mean "not reported". */
    public record SessionView(
            String id,
            String agent,
            String ticket,
            String model,
            boolean open,
            Boolean failed,
            Integer turns,
            Long inputTokensProvided,
            Long outputTokensProvided,
            Double costUsdEstimated,
            String startedAt,
            String endedAt,
            /** completed, failed, interrupted (left open by the previous daemon); null while open. */
            String outcome,
            /**
             * The cap the session ran under, when it ended without reporting a cost: the budget counts it as an upper
             * bound (never as a measure); null when a cost was reported, and for a simulated session.
             */
            Double costUsdBound) {
        /** A simulated session paid nothing: no cost is shown, whatever the recording it replays once cost. */
        static SessionView of(AgentSession s, dev.floor42.domain.Origin origin) {
            return new SessionView(
                    s.id(),
                    s.agent().value(),
                    s.ticket().value(),
                    s.model(),
                    s.isOpen(),
                    s.failed(),
                    s.turns(),
                    s.inputTokensProvided(),
                    s.outputTokensProvided(),
                    origin == dev.floor42.domain.Origin.REAL ? s.costUsdEstimated() : null,
                    s.startedAt().toString(),
                    s.endedAt() == null ? null : s.endedAt().toString(),
                    s.outcome(),
                    origin == dev.floor42.domain.Origin.REAL ? s.costUsdBound() : null);
        }
    }

    public record CheckRunView(
            String workspace,
            String command,
            Integer exitCode,
            boolean timedOut,
            boolean passed,
            long durationMs,
            String output,
            String head,
            String trigger,
            String at) {
        static CheckRunView of(CheckRun r) {
            return new CheckRunView(
                    r.workspace().value(),
                    r.command(),
                    r.exitCode(),
                    r.timedOut(),
                    r.passed(),
                    r.durationMs(),
                    r.output(),
                    r.head(),
                    r.trigger(),
                    r.at().toString());
        }
    }

    public record MissionView(
            String id,
            String title,
            String objective,
            String status,
            String origin,
            String workspace,
            String outcome,
            List<String> permissions,
            List<TicketView> tickets,
            List<ActionView> actions,
            List<QuestionView> questions,
            List<IncidentView> incidents,
            List<SessionView> sessions,
            /** The director's checks, their fields at this level. */
            @com.fasterxml.jackson.annotation.JsonUnwrapped Checks checks,
            Double budgetUsd,
            /** True once a budget was set or "no cap" chosen, both journaled: a real plan is approved only then. */
            boolean capDecided,
            /** What the mission spent, its fields at this level: the estimates apart from the one upper bound. */
            @com.fasterxml.jackson.annotation.JsonUnwrapped Spend spend,
            List<CheckRunView> checkRuns,
            List<ScoreLine> scorecard,
            CommitteeView committee,
            /** Every committee of the mission, oldest first; the last one is {@code committee}. */
            List<CommitteeSummaryView> committees,
            RetroView retro,
            /** The ticket of the planner's staffing review while it runs; null otherwise. */
            String staffingReview,
            /** The director's meeting open about this mission (ADR-0047); null when none is. */
            MeetingViews.MeetingView meeting,
            /** The workflow the mission follows, pinned at its creation (ADR-0050), or why this build does not run it. */
            WorkflowViews.MissionWorkflowView workflow,
            /** Where the accepted deliverables land (ADR-0051); null for a mission created without a folder. */
            DeliveryHomeView delivery,
            /** Put away by the director once closed (ADR-0054): out of the lists, restorable. */
            boolean archived,
            /** When the mission was completed or abandoned (ISO-8601); null while it runs. */
            String closedAt) {
        public MissionView {
            actions = actions == null ? null : Collections.unmodifiableList(new ArrayList<>(actions));
            checkRuns = checkRuns == null ? null : Collections.unmodifiableList(new ArrayList<>(checkRuns));
            committees = committees == null ? null : Collections.unmodifiableList(new ArrayList<>(committees));
            incidents = incidents == null ? null : Collections.unmodifiableList(new ArrayList<>(incidents));
            permissions = permissions == null ? null : Collections.unmodifiableList(new ArrayList<>(permissions));
            questions = questions == null ? null : Collections.unmodifiableList(new ArrayList<>(questions));
            scorecard = scorecard == null ? null : Collections.unmodifiableList(new ArrayList<>(scorecard));
            sessions = sessions == null ? null : Collections.unmodifiableList(new ArrayList<>(sessions));
            tickets = tickets == null ? null : Collections.unmodifiableList(new ArrayList<>(tickets));
        }
        /** The command Floor42 runs to check the agents' work (null: none), and whether after every delivery. */
        public record Checks(String checkCommand, boolean checksAutomatic) {}

        /** The mission's spend, as {@code Verification} counts it (ADR-0049). */
        public record Spend(
                /** The costs Claude Code estimated; a session that reported none adds nothing here. */
                double spentUsdEstimated,
                /**
                 * The mission's one upper bound, never an estimate: the caps of the sessions that ended without
                 * reporting a cost, and the meeting turns charged their budget, each once; the budget counts it on top
                 * of {@code spentUsdEstimated}. Null when nothing is bounded: 0 would read as a real figure.
                 */
                Double spentUsdBound,
                /** How many sessions (a charged turn with none of its own included) count in {@code spentUsdBound}. */
                int sessionsBounded) {
            static Spend of(Company c, Mission m) {
                var v = c.verification();
                int bounded = v.sessionsBounded(m.id());
                return new Spend(v.spentUsdEstimated(m.id()), bounded == 0 ? null : v.spentUsdBound(m.id()), bounded);
            }
        }

        static MissionView of(Company c, Mission m) {
            return new MissionView(
                    m.id().value(),
                    m.title(),
                    m.objective(),
                    m.status().name(),
                    m.origin().name(),
                    m.workspace(),
                    m.outcome(),
                    m.policy().rules().stream().map(Object::toString).toList(),
                    TicketView.all(c, m.id()),
                    c.actions().of(m.id()).stream().map(ActionView::of).toList(),
                    QuestionView.all(c, m.id()),
                    incidents(c, m),
                    sessions(c, m),
                    new Checks(m.checkCommand(), m.checksAutomatic()),
                    m.budgetUsd(),
                    m.capDecided(),
                    Spend.of(c, m),
                    checkRuns(c, m),
                    Scorecard.of(c, m),
                    c.committees().latest(m.id()).map(CommitteeView::of).orElse(null),
                    committees(c, m),
                    c.retros().find(m.id()).map(r -> RetroView.of(c, r)).orElse(null),
                    staffingReview(c, m),
                    meeting(c, m),
                    WorkflowViews.MissionWorkflowView.of(c, m.id()),
                    DeliveryHomeView.of(c, m.id()),
                    m.archived(),
                    m.closedAt() == null ? null : m.closedAt().toString());
        }

        private static MeetingViews.MeetingView meeting(Company c, Mission m) {
            return c.meetings()
                    .openOn(m.id())
                    .map(x -> MeetingViews.MeetingView.of(c, x, null))
                    .orElse(null);
        }

        private static List<SessionView> sessions(Company c, Mission m) {
            return c.sessions().of(m.id()).stream()
                    .map(s -> SessionView.of(s, m.origin()))
                    .toList();
        }

        private static List<CheckRunView> checkRuns(Company c, Mission m) {
            return c.verification().checkRuns(m.id()).stream()
                    .map(CheckRunView::of)
                    .toList();
        }

        private static List<CommitteeSummaryView> committees(Company c, Mission m) {
            return c.committees().of(m.id()).stream()
                    .map(CommitteeSummaryView::of)
                    .toList();
        }

        private static List<IncidentView> incidents(Company c, Mission m) {
            return c.actions().incidents().stream()
                    .filter(i -> i.mission().equals(m.id()))
                    .map(IncidentView::of)
                    .toList();
        }

        private static String staffingReview(Company c, Mission m) {
            return c.staff()
                    .staffingReview(m.id())
                    .filter(r -> !r.advised())
                    .map(r -> r.ticket().value())
                    .orElse(null);
        }
    }
}
