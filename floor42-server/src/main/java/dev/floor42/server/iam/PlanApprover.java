package dev.floor42.server.iam;

import dev.floor42.domain.MissionId;

/**
 * The approval of a mission's plan with its cap, as the plan approval route does it with IAM mode off. In IAM mode
 * {@link StepUpApprovals} calls it once the director's password step-up succeeded; it throws when the plan cannot be
 * approved (the mission is no longer awaiting approval, the cap is refused).
 */
public interface PlanApprover {

    /** {@code budgetUsd} null with {@code noCap} false keeps the budget already set. */
    void approve(MissionId mission, Double budgetUsd, boolean noCap);

    /** Throws when the mission has no plan awaiting approval (checked before the director is asked for a password). */
    void requireAwaiting(MissionId mission);
}
