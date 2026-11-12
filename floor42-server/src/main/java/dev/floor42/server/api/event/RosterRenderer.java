package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.event.RosterEvent;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsResumed;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsSuspended;
import dev.floor42.domain.event.RosterEvent.AgentDismissed;
import dev.floor42.domain.event.RosterEvent.AgentHired;
import dev.floor42.domain.event.RosterEvent.AgentMarkedCore;
import dev.floor42.domain.event.RosterEvent.AgentReconfigured;
import dev.floor42.domain.event.RosterEvent.AgentRoleAssigned;
import dev.floor42.domain.event.RosterEvent.AgentUnmarkedCore;

/** The staff's lines: hiring, roles, models, leaving. */
final class RosterRenderer implements EventRenderer<RosterEvent> {

    @Override
    public EventLine render(Company c, RosterEvent event) {
        return switch (event) {
            case AgentHired e -> hired(e);
            case AgentAssignmentsSuspended e ->
                agent(e.agent()).param("reason", e.reason()).build();
            case AgentAssignmentsResumed e -> agent(e.agent()).build();
            case AgentReconfigured e ->
                line(e.effort() == null ? "model" : "effort")
                        .agent(e.agent())
                        .param("agent", e.agent())
                        .param("model", e.model())
                        .param("effort", e.effort())
                        .build();
            case AgentRoleAssigned e ->
                agent(e.agent()).param("workRole", e.workRole()).build();
            case AgentDismissed e ->
                agent(e.agent()).param("reason", e.reason()).build();
            case AgentMarkedCore e -> agent(e.agent()).build();
            case AgentUnmarkedCore e -> agent(e.agent()).build();
        };
    }

    private static EventLine hired(AgentHired e) {
        return agent(e.agent())
                .param("displayName", e.displayName())
                .param("role", e.role())
                .param("provider", e.provider())
                .param("model", e.model())
                .param("effort", e.effort())
                .param("skills", e.skills())
                .build();
    }

    /** A line about one agent: the scene points at it. */
    private static EventLine.Builder agent(AgentId agent) {
        return line().agent(agent).param("agent", agent);
    }
}
