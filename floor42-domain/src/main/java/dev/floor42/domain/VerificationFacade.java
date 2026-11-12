package dev.floor42.domain;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.VerificationEvent.ChecksRan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What keeps the work honest and affordable: the mission's checks and their runs, its budget, and the plan's usage
 * windows. Reached by {@link Company#verification()}; the rules live in the verification area.
 */
public final class VerificationFacade {

    private final Verification verification;

    VerificationFacade(Verification verification) {
        this.verification = verification;
    }

    public List<CheckRun> checkRuns(MissionId mission) {
        return verification.checkRuns(mission);
    }

    /** What the mission's sessions cost, as estimated by the runtime; sessions without an estimate add nothing here. */
    public double spentUsdEstimated(MissionId mission) {
        return verification.spentUsdEstimated(mission);
    }

    /** The caps of the sessions that ended without reporting a cost: an upper bound, never an estimate. */
    public double spentUsdBound(MissionId mission) {
        return verification.spentUsdBound(mission);
    }

    /**
     * The part of {@link #spentUsdBound} no session carries: the meeting turns charged their budget for want of an
     * estimate, each once (ADR-0049), for the views that split the bound by period, speaker or ticket.
     */
    public List<ChargedTurn> chargedTurns(MissionId mission) {
        return verification.unboundTurns(mission).toList();
    }

    /** How many sessions count for their cap ({@link #spentUsdBound}), having reported no cost. */
    public int sessionsBounded(MissionId mission) {
        return verification.sessionsBounded(mission);
    }

    /** What the budget counts: the estimates plus the bounds. */
    public double spentUsdCounted(MissionId mission) {
        return Usd.usd(verification.spentMicroUsd(mission));
    }

    /** What is left of the mission's budget, by the estimates and bounds; empty when the mission has no budget. */
    public Optional<Double> budgetLeftUsd(MissionId mission) {
        return verification.budgetLeftUsd(mission);
    }

    /** True when a budget is set and the spending counted (estimates and bounds) reached it: no session may start. */
    public boolean budgetReached(MissionId mission) {
        return verification.budgetReached(mission);
    }

    /** The tests of this graph node that failed so far: each is a rejection of the node's single rework (ADR-0062). */
    public int nodeTestFailures(TicketId node) {
        return verification.nodeTestFailures(node);
    }

    public PlanUsage planUsage() {
        return verification.planUsage();
    }

    /** Would a session of this model, started now, likely be cut by a usage window? */
    public Optional<PlanUsage.Risk> planLimitRisk(String model, double threshold, Instant now) {
        return verification.planLimitRisk(model, threshold, now);
    }

    public List<EventEnvelope> configureChecks(CommandContext ctx, MissionId id, String command, boolean automatic) {
        return verification.configureChecks(ctx, id, command, automatic);
    }

    /** Before running: checks are configured, the workspace exists, and no agent is at work in it. */
    public void checkChecksCanRun(CommandContext ctx, MissionId id, AgentId agent) {
        verification.checkChecksCanRun(ctx, id, agent);
    }

    public List<EventEnvelope> recordChecks(
            CommandContext ctx,
            MissionId id,
            AgentId agent,
            String command,
            Integer exitCode,
            boolean timedOut,
            long durationMs,
            String output,
            String head,
            String trigger) {
        return verification.recordChecks(
                ctx, new ChecksRan(id, agent, command, exitCode, timedOut, durationMs, output, head, trigger));
    }

    public List<EventEnvelope> setBudget(CommandContext ctx, MissionId id, Double usd) {
        return verification.setBudget(ctx, id, usd);
    }

    /** Floor42 asks once: while the question is open, a second report changes nothing. */
    public List<EventEnvelope> reportBudgetReached(CommandContext ctx, MissionId id) {
        return verification.reportBudgetReached(ctx, id);
    }

    /** The runtime reported a usage window during a session: Floor42 records it as measured by Claude Code. */
    public List<EventEnvelope> observePlanUsage(
            CommandContext ctx, String session, String window, double utilization, long resetsAt, String status) {
        return verification.observePlanUsage(ctx, session, window, utilization, resetsAt, status);
    }

    /**
     * Before a session that the plan's usage limit would likely cut, Floor42 holds the step and asks once: wait for the
     * window to reset, run anyway, or stop. A cut session loses its work in progress.
     */
    public List<EventEnvelope> reportPlanLimit(CommandContext ctx, MissionId id, String model, PlanUsage.Risk risk) {
        return verification.reportPlanLimit(ctx, id, model, risk);
    }
}
