package dev.floor42.domain.event;

import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.ActionId;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.IncidentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;

/** Tool calls and the incidents of calls that ran without authorization. */
public sealed interface ActionEvent extends DomainEvent {

    /** @param ticket nullable: an action may serve the mission as a whole */
    record ActionRequested(
            ActionId action,
            MissionId mission,
            TicketId ticket,
            AgentId agent,
            ActionCategory category,
            String tool,
            String summary)
            implements ActionEvent {}

    record ActionAuthorized(ActionId action, String basis) implements ActionEvent {}

    record ActionDenied(ActionId action, DenialReason reason, String detail) implements ActionEvent {}

    /** @param authorized false when the tool ran although Floor42 never authorized it (always paired with an incident) */
    record ActionExecuted(ActionId action, String output, boolean authorized) implements ActionEvent {}

    record ActionFailed(ActionId action, String error) implements ActionEvent {}

    record ActionConfirmed(ActionId action, String evidence) implements ActionEvent {}

    /** Claude Code reports an authorized tool call still running after {@code seconds} (its heartbeat). */
    record ActionProgressed(ActionId action, long seconds) implements ActionEvent {}

    /** @param action nullable */
    record IncidentRaised(
            IncidentId incident, MissionId mission, Criticality criticality, String description, ActionId action)
            implements ActionEvent {}
}
