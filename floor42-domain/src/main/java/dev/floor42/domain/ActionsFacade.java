package dev.floor42.domain;

import dev.floor42.domain.event.ActionEvent.ActionRequested;
import dev.floor42.domain.event.EventEnvelope;
import java.util.List;
import java.util.Optional;

/**
 * The agents' tool calls, governed by Floor42: requested, decided, run, confirmed; and the incidents they raised.
 * Reached by {@link Company#actions()}; the rules live in the action log.
 */
public final class ActionsFacade {

    private final ActionLog actionLog;

    ActionsFacade(ActionLog actionLog) {
        this.actionLog = actionLog;
    }

    public List<Action> of(MissionId mission) {
        return actionLog.actions(mission);
    }

    public Optional<Action> find(ActionId id) {
        return actionLog.action(id);
    }

    /** An incident number held by a journal row this version could not read: no new incident takes it. */
    public void reserveIncident(IncidentId id) {
        actionLog.reserveIncident(id);
    }

    public List<Incident> incidents() {
        return actionLog.incidents();
    }

    /**
     * An agent asks to use a tool. Floor42 decides immediately: allowed or denied by the mission policy, or turned into
     * an approval question for the director. Decisions are recorded as Floor42's, not the agent's.
     */
    public List<EventEnvelope> request(
            CommandContext ctx,
            ActionId id,
            MissionId missionId,
            TicketId ticketId,
            AgentId agentId,
            ActionCategory category,
            String tool,
            String summary) {
        return request(ctx, id, missionId, ticketId, agentId, category, tool, summary, null);
    }

    /**
     * @param confinementViolation why the call leaves the agent's workspace, as checked by the runtime against the
     *     real file system; null when it stays inside or does not touch files. Such a call is denied before any
     *     permission rule is looked at.
     */
    public List<EventEnvelope> request(
            CommandContext ctx,
            ActionId id,
            MissionId missionId,
            TicketId ticketId,
            AgentId agentId,
            ActionCategory category,
            String tool,
            String summary,
            String confinementViolation) {
        return actionLog.request(
                ctx,
                new ActionRequested(id, missionId, ticketId, agentId, category, tool, summary),
                confinementViolation);
    }

    /**
     * Records Claude Code's word that an authorized call is still running. Nothing is recorded once the call ended,
     * nor for a time not later than the last one.
     */
    public List<EventEnvelope> recordProgress(CommandContext ctx, ActionId id, long seconds) {
        return actionLog.progress(ctx, id, seconds);
    }

    /**
     * The runtime reports what happened to a tool call. The classification rule found in the runtime spike applies: a
     * call Floor42 never authorized did not run under governance, so an error is a block, not a failure, and a success
     * is an incident that suspends the mission.
     */
    public List<EventEnvelope> recordOutcome(CommandContext ctx, ActionId id, boolean success, String output) {
        return success ? actionLog.succeeded(ctx, id, output) : actionLog.failed(ctx, id, output);
    }

    /**
     * A tool call the runtime reported but that never reached Floor42's hook: Claude Code refused it itself (a tool not
     * offered to this session, for instance). It is recorded as it happened, so the journal has no hole: as a call
     * refused upstream when it failed, or as an execution without an Floor42 authorization (a critical incident) if it
     * succeeded.
     */
    public List<EventEnvelope> recordUnseenCall(
            CommandContext ctx,
            ActionId id,
            MissionId missionId,
            TicketId ticketId,
            AgentId agentId,
            ActionCategory category,
            String tool,
            String summary,
            boolean success,
            String output) {
        return actionLog.unseen(
                ctx, new ActionRequested(id, missionId, ticketId, agentId, category, tool, summary), success, output);
    }

    /** Independent confirmation of a result (a passing check, a reviewer reproducing it). Never by the same agent. */
    public List<EventEnvelope> confirm(CommandContext ctx, ActionId id, String evidence) {
        return actionLog.confirm(ctx, id, evidence);
    }
}
