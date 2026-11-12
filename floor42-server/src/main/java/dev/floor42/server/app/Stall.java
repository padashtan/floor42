package dev.floor42.server.app;

import java.time.Instant;

/**
 * Where the dispatcher stands on one mission, by its own rule and not by a guess: a mission is stuck only when the
 * dispatcher would start a task now and has not, for the stall setting. Everything that rightly holds work back is
 * named instead, so the alert does not cry wolf. Computed on each call; nothing here is recorded.
 *
 * @param state {@code RUNNING} (the dispatcher holds a task of the mission), {@code STUCK} (it would start {@code work}
 *     on {@code ticket} with {@code agent}, yet nothing of the mission ran or happened since {@code since}, for the
 *     stall setting or longer), {@code DUE} (the same, for less long), {@code WAITING} (nothing can start, for
 *     {@code reason}) or {@code IDLE} (nothing to do, or a mission the dispatcher does not drive)
 * @param reason for WAITING: {@code QUESTION} (an open question holds the mission or its ticket), {@code HELD_PATCH}
 *     ({@code agent}'s finished work waits in the workshop), {@code COMMITTEE_DIRECTOR} (the decision record awaits the
 *     ruling), {@code PROPOSED} (tickets wait for approval), {@code RESTING} (until {@code until}, after a usage
 *     window), {@code AGENT_BUSY} ({@code agent} works on mission {@code elsewhere}), {@code SANDBOX_MEASURE} (the
 *     director answered "I install it" and the sandbox's new measure is not recorded yet); for STUCK and DUE
 *     {@code WOULD_START}; for IDLE {@code NOTHING} or {@code NOT_DISPATCHED}
 * @param work what the dispatcher would start: PRODUCE, PLAN, REVIEW, CONSULT, COMMITTEE or ASK_DEPENDENCY
 * @param elsewhere the mission the awaited agent works on, when known
 * @param since STUCK and DUE: the last sign of the mission (a task ended, an event of it, the daemon's start); WAITING:
 *     when the question was asked, when known
 * @param until RESTING: when the mission resumes
 */
public record Stall(
        String state,
        String reason,
        String work,
        String ticket,
        String agent,
        String elsewhere,
        Instant since,
        Instant until) {

    static Stall of(String state, String reason) {
        return new Stall(state, reason, null, null, null, null, null, null);
    }

    public boolean stuck() {
        return "STUCK".equals(state);
    }
}
