package dev.floor42.domain;

/** Why a question was asked: it decides what the director answer does. */
public enum QuestionKind {
    /** Free question; the answer is information for the agents. */
    OPEN,
    /** Approval request for one action; answer ALLOW or DENY. */
    ACTION_APPROVAL,
    /** A reviewer could not verify a ticket; the director decides what happens to it. */
    REVIEW_ESCALATION,
    /** The mission spent its budget: no new session starts until the director raises it. */
    BUDGET_REACHED,
    /** The committee's decision record awaits the director's ruling. */
    COMMITTEE_ARBITRATION,
    /** The next session would likely be cut by the plan's usage limit: run anyway, wait for the reset, or stop. */
    PLAN_LIMIT,
    /** The planner advises reinforcing the team: hire the developers it names, or not now. Blocks nothing. */
    STAFFING,
    /**
     * The business analyst asks what a committee's members could not settle without the director; once every one is
     * answered, the committee debates one more round with the answers.
     */
    CLARIFICATION
}
