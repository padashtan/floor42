package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireOpen;
import static dev.floor42.domain.Rules.requireSystem;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.DeskEvent.QuestionAsked;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.domain.event.MissionEvent.ChecksConfigured;
import dev.floor42.domain.event.VerificationEvent;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import dev.floor42.domain.event.VerificationEvent.PlanUsageObserved;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What Floor42 measured about the work (spec sections 12, 15, 16): checks run in a workspace, the plan's usage windows,
 * and what the sessions cost against the mission's budget. Amounts are added and compared in whole micro-USD.
 */
final class Verification {

    private static final List<String> BUDGET_OPTIONS =
            List.of(OptionCode.RAISE_BUDGET.label(), OptionCode.STOP_HERE.label());
    private static final List<String> PLAN_LIMIT_OPTIONS =
            List.of(OptionCode.WAIT_FOR_RESET.label(), OptionCode.RUN_ANYWAY.label(), OptionCode.STOP_HERE.label());

    private final CompanyView view;
    private final List<CheckRun> checkRuns = new ArrayList<>();
    private final PlanUsage planUsage = new PlanUsage();

    Verification(CompanyView view) {
        this.view = view;
    }

    List<CheckRun> checkRuns(MissionId mission) {
        return checkRuns.stream().filter(r -> r.mission().equals(mission)).toList();
    }

    /** The tests of this node that failed so far: each one is a rejection of its single rework (ADR-0062). */
    int nodeTestFailures(TicketId node) {
        return (int) checkRuns.stream()
                .filter(r -> r.failed() && r.nodeTest().filter(node::equals).isPresent())
                .count();
    }

    PlanUsage planUsage() {
        return planUsage;
    }

    Optional<PlanUsage.Risk> planLimitRisk(String model, double threshold, Instant now) {
        return planUsage.risk(
                model,
                threshold,
                now,
                s -> view.session(s).map(AgentSession::model).orElse(null));
    }

    /** The costs Claude Code estimated for the mission's sessions; a session that reported none adds nothing here. */
    double spentUsdEstimated(MissionId mission) {
        return Usd.usd(micros(paid(mission).stream().map(AgentSession::costUsdEstimated)));
    }

    /**
     * The one upper bound of the mission's spend, never an estimate nor a measure: the cap of each session that ended
     * without reporting a cost, and the budget of each meeting turn charged for want of an estimate (ADR-0049) whose
     * session adds nothing itself. A turn's session is a session of the mission: its cap counts once, through it.
     */
    double spentUsdBound(MissionId mission) {
        return Usd.usd(boundMicroUsd(mission));
    }

    /** How many sessions count for their cap, having reported no cost, a charged turn with no session of its own too. */
    int sessionsBounded(MissionId mission) {
        return (int) (paid(mission).stream().filter(s -> boundOnly(s) != null).count()
                + unboundTurns(mission).count());
    }

    /**
     * What the budget counts: the estimates plus the bound, never less than was possibly spent. Ten sessions of 0.10
     * add up to 0.9999999999999999 in double, under a 1.00 cap: amounts add as integers.
     */
    long spentMicroUsd(MissionId mission) {
        return micros(paid(mission).stream().map(AgentSession::costUsdEstimated)) + boundMicroUsd(mission);
    }

    private long boundMicroUsd(MissionId mission) {
        return micros(paid(mission).stream().map(Verification::boundOnly))
                + micros(unboundTurns(mission).map(ChargedTurn::usd));
    }

    private static Double boundOnly(AgentSession s) {
        return s.costUsdEstimated() == null ? s.costUsdBound() : null;
    }

    /** Charged turns whose session counts for nothing: none on record, still open, or ended before caps were kept. */
    Stream<ChargedTurn> unboundTurns(MissionId mission) {
        if (simulated(mission)) return Stream.empty();
        return view.meetingsOf(mission).stream()
                .flatMap(mt -> mt.rounds().stream()
                        .flatMap(r -> r.turns().stream())
                        .filter(t -> t.budgetUsd() != null)
                        .filter(t -> t.session() == null
                                || view.session(t.session())
                                        .map(AgentSession::costUsdCounted)
                                        .isEmpty())
                        .map(t -> new ChargedTurn(mission, mt.ticket(), t.attendee(), t.budgetUsd(), t.at())));
    }

    private static long micros(Stream<Double> amounts) {
        return amounts.filter(Objects::nonNull).mapToLong(Usd::micros).sum();
    }

    /** A simulated mission calls no model: whatever a replayed recording reported, it spent nothing (ADR-0045). */
    private List<AgentSession> paid(MissionId mission) {
        return simulated(mission) ? List.of() : view.sessions(mission);
    }

    private boolean simulated(MissionId mission) {
        return view.mission(mission).map(m -> m.origin() != Origin.REAL).orElse(false);
    }

    Optional<Double> budgetLeftUsd(MissionId mission) {
        Mission m = view.mission(mission).orElse(null);
        if (m == null || m.budgetUsd == null) return Optional.empty();
        return Optional.of(Usd.usd(Math.max(0, Usd.micros(m.budgetUsd) - spentMicroUsd(mission))));
    }

    boolean budgetReached(MissionId mission) {
        Mission m = view.mission(mission).orElse(null);
        return m != null && m.budgetUsd != null && spentMicroUsd(mission) >= Usd.micros(m.budgetUsd);
    }

    List<EventEnvelope> configureChecks(CommandContext ctx, MissionId id, String command, boolean automatic) {
        requireDirector(ctx);
        Mission m = view.missionFor(ctx, id);
        requireOpen(m);
        require(m.workspace() != null, Refusal.NO_REPOSITORY, id + " has no repository to check");
        String c = command == null || command.isBlank() ? null : command.strip();
        require(c != null || !automatic, Refusal.INVALID_ARGUMENT, "Automatic checks need a command");
        return view.record(ctx, id).emit(new ChecksConfigured(id, c, automatic)).events();
    }

    void checkChecksCanRun(CommandContext ctx, MissionId id, AgentId agent) {
        Mission m = view.missionFor(ctx, id);
        requireOpen(m);
        require(m.checkCommand != null, Refusal.NO_CHECKS, "No checks configured for " + id);
        require(view.workspace(id, agent).isPresent(), Refusal.UNKNOWN_WORKSPACE, agent + " has no workspace on " + id);
        Rules.requireNoAgentAtWork(view, id);
    }

    List<EventEnvelope> recordChecks(CommandContext ctx, ChecksRan ran) {
        requireSystem(ctx, "Only Floor42 records what it measured");
        MissionId id = ran.mission();
        // A run that was going when the mission was cancelled ends unrecorded: nothing measured after the end.
        requireOpen(view.missionFor(ctx, id));
        require(
                view.workspace(id, ran.workspace()).isPresent(),
                Refusal.UNKNOWN_WORKSPACE,
                ran.workspace() + " has no workspace on " + id);
        requireText(ran.command(), "command");
        requireText(ran.trigger(), "trigger");
        return view.record(ctx, id).emit(ran).events();
    }

    List<EventEnvelope> setBudget(CommandContext ctx, MissionId id, Double usd) {
        requireDirector(ctx);
        requireOpen(view.missionFor(ctx, id));
        require(usd == null || usd > 0, Refusal.INVALID_ARGUMENT, "A budget is positive, or none");
        return view.record(ctx, id).emit(new BudgetSet(id, usd)).events();
    }

    List<EventEnvelope> observePlanUsage(
            CommandContext ctx, String session, String window, double utilization, long resetsAt, String status) {
        requireSystem(ctx, "Only the runtime reports usage");
        AgentSession s = view.session(session).orElse(null);
        require(s != null, Refusal.UNKNOWN_SESSION, "No session " + session);
        requireText(window, "window");
        require(utilization >= 0, Refusal.INVALID_ARGUMENT, "Utilization is a fraction");
        return view.record(ctx, s.mission())
                .emit(new PlanUsageObserved(session, window, utilization, resetsAt, status))
                .events();
    }

    /** Floor42 holds the step and asks once: wait for the window to reset, run anyway, or stop. */
    List<EventEnvelope> reportPlanLimit(CommandContext ctx, MissionId id, String model, PlanUsage.Risk risk) {
        requireSystem(ctx, "Only Floor42 reports the plan limit");
        view.missionFor(ctx, id);
        if (asked(id, QuestionKind.PLAN_LIMIT)) return List.of();
        String window = PlanUsage.label(risk.window());
        return view.record(ctx, id)
                .emit(new QuestionAsked(
                        view.nextQuestionId(),
                        id,
                        QuestionKind.PLAN_LIMIT,
                        null,
                        "The " + window + " usage limit may cut the next session",
                        String.format(
                                Locale.ROOT,
                                "%s window at %.0f%% (measured by Claude Code); %s. It resets at %s. A session cut by"
                                        + " the limit loses its work in progress.",
                                window,
                                risk.utilization() * 100,
                                expectedIncrease(risk, model),
                                risk.resetsAt()),
                        PLAN_LIMIT_OPTIONS,
                        OptionCode.WAIT_FOR_RESET.label(),
                        Criticality.HIGH,
                        true,
                        null,
                        null,
                        Phrase.of("planLimit", "window", window),
                        OptionCode.codesOf(PLAN_LIMIT_OPTIONS)))
                .events();
    }

    private static String expectedIncrease(PlanUsage.Risk risk, String model) {
        if (risk.expectedIncrease() == null) return "how much one session adds is not known yet";
        if (risk.expectedIncrease() < 0.01) {
            return "a session adds less than 1% (estimated from earlier sessions; readings are in whole percents;"
                    + " use outside Floor42 counts too)";
        }
        return String.format(
                Locale.ROOT,
                "a session adds about %.0f%% (estimated from earlier sessions, of %s when known; use outside"
                        + " Floor42 counts too)",
                risk.expectedIncrease() * 100,
                model);
    }

    /** Floor42 asks once: while the question is open, a second report changes nothing. */
    List<EventEnvelope> reportBudgetReached(CommandContext ctx, MissionId id) {
        requireSystem(ctx, "Only Floor42 reports the budget");
        Mission m = view.missionFor(ctx, id);
        require(budgetReached(id), Refusal.BUDGET_AVAILABLE, id + " has budget left");
        if (asked(id, QuestionKind.BUDGET_REACHED)) return List.of();
        Phrase phrase = budgetPhrase(id, m.budgetUsd);
        return view.record(ctx, id)
                .emit(new QuestionAsked(
                        view.nextQuestionId(),
                        id,
                        QuestionKind.BUDGET_REACHED,
                        null,
                        "Budget of " + id + " reached",
                        budgetContext(phrase.params()) + " No new agent session starts until you raise it.",
                        BUDGET_OPTIONS,
                        null,
                        Criticality.HIGH,
                        true,
                        null,
                        null,
                        phrase,
                        OptionCode.codesOf(BUDGET_OPTIONS)))
                .events();
    }

    /** All estimates: "budget"; with sessions counted for their cap, "budgetBounded" says the bound apart. */
    private Phrase budgetPhrase(MissionId id, double budgetUsd) {
        String spent = String.format(Locale.ROOT, "%.4f", Usd.usd(spentMicroUsd(id)));
        String budget = String.format(Locale.ROOT, "%.4f", budgetUsd);
        int bounded = sessionsBounded(id);
        if (bounded == 0) return Phrase.of("budget", "mission", id.value(), "spent", spent, "budget", budget);
        return Phrase.of(
                "budgetBounded",
                "mission",
                id.value(),
                "spent",
                spent,
                "budget",
                budget,
                "estimated",
                String.format(Locale.ROOT, "%.4f", spentUsdEstimated(id)),
                "bound",
                String.format(Locale.ROOT, "%.4f", spentUsdBound(id)),
                "bounded",
                Integer.toString(bounded));
    }

    private static String budgetContext(java.util.Map<String, String> p) {
        if (!p.containsKey("bounded"))
            return "Estimated spending " + p.get("spent") + " USD of a " + p.get("budget")
                    + " USD budget (estimates by the runtime).";
        return "Spending counted " + p.get("spent") + " USD of a " + p.get("budget") + " USD budget: estimated "
                + p.get("estimated") + " USD by the runtime + up to " + p.get("bound") + " USD for "
                + p.get("bounded") + " session(s) with no reported cost (the cap each ran under).";
    }

    private boolean asked(MissionId id, QuestionKind kind) {
        return view.questions(id).stream().anyMatch(q -> q.kind() == kind && q.status() == QuestionStatus.OPEN);
    }

    /** The director's answer to a reached budget: raised by half of what was budgeted or spent, whichever is more. */
    void budgetAnswered(Recorder r, Question q, String chosen) {
        Mission m = view.mission(q.mission()).orElseThrow();
        if (q.codeOf(chosen) == OptionCode.RAISE_BUDGET && m.budgetUsd != null) {
            double base = Usd.usd(Math.max(Usd.micros(m.budgetUsd), spentMicroUsd(m.id())));
            r.emit(new BudgetSet(m.id(), Math.round(base * 1.5 * 10_000) / 10_000.0));
        }
    }

    /** The only way this area's state changes. */
    void apply(VerificationEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case ChecksRan e -> on(e, at);
            case PlanUsageObserved e -> on(e, at);
        }
    }

    void on(ChecksRan e, Instant at) {
        checkRuns.add(new CheckRun(
                e.mission(),
                e.workspace(),
                e.command(),
                e.exitCode(),
                e.timedOut(),
                e.durationMs(),
                e.output(),
                e.head(),
                e.trigger(),
                at));
    }

    void on(PlanUsageObserved e, Instant at) {
        planUsage.add(new PlanUsage.Reading(
                e.window(),
                e.utilization(),
                Instant.ofEpochSecond(e.resetsAtEpochSecond()),
                e.status(),
                e.session(),
                at));
    }
}
