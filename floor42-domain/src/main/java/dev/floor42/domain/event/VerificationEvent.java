package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;

/** What Floor42 measured: checks run and usage windows. */
public sealed interface VerificationEvent extends DomainEvent {

    record PlanUsageObserved(String session, String window, double utilization, long resetsAtEpochSecond, String status)
            implements VerificationEvent {}

    /**
     * Floor42 ran the checks and measured the outcome.
     *
     * @param exitCode null when the run timed out or could not start
     */
    record ChecksRan(
            MissionId mission,
            AgentId workspace,
            String command,
            Integer exitCode,
            boolean timedOut,
            long durationMs,
            String output,
            String head,
            String trigger)
            implements VerificationEvent {}
}
