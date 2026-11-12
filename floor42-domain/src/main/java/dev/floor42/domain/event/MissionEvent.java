package dev.floor42.domain.event;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.PermissionRule;

/** A mission's life, its permission table, checks and budget. */
public sealed interface MissionEvent extends DomainEvent {

    /** @param workspace the repository a real mission works on; null for simulated missions */
    record MissionCreated(MissionId mission, String title, String objective, String workspace)
            implements MissionEvent {}

    record MissionPlanSubmitted(MissionId mission) implements MissionEvent {}

    record MissionPlanApproved(MissionId mission) implements MissionEvent {}

    record MissionPlanRejected(MissionId mission, String reason) implements MissionEvent {}

    record MissionSuspended(MissionId mission, String reason) implements MissionEvent {}

    record MissionResumed(MissionId mission) implements MissionEvent {}

    record MissionCompleted(MissionId mission, String summary) implements MissionEvent {}

    record MissionCancelled(MissionId mission, String reason) implements MissionEvent {}

    record PermissionRuleAdded(MissionId mission, PermissionRule rule) implements MissionEvent {}

    record PermissionRuleRemoved(MissionId mission, PermissionRule rule) implements MissionEvent {}

    /** From now on, tickets the director does not create start as proposals (ADR-0017). */
    record TicketValidationEnabled(MissionId mission) implements MissionEvent {}

    /**
     * The director's checks for a mission: a command Floor42 runs in an agent's workspace.
     *
     * @param command nullable: no checks
     * @param automatic run after every delivery, before the independent review
     */
    record ChecksConfigured(MissionId mission, String command, boolean automatic) implements MissionEvent {}

    /** @param usd nullable: no limit. Compared with the runtime's own cost estimates. */
    record BudgetSet(MissionId mission, Double usd) implements MissionEvent {}

    /**
     * The director put a closed mission away (ADR-0054): it leaves the board and the mission lists, keeps its journal
     * and its worktrees, and can be restored. A view of the director's, never a change of the mission's outcome.
     */
    record MissionArchived(MissionId mission) implements MissionEvent {}

    /** The director brought an archived mission back to the lists (ADR-0054); its status and outcome are unchanged. */
    record MissionUnarchived(MissionId mission) implements MissionEvent {}
}
