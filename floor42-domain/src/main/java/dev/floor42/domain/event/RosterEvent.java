package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The staff: hiring, roles, models, leaving. */
public sealed interface RosterEvent extends DomainEvent {

    record AgentHired(
            AgentId agent,
            String displayName,
            String role,
            String provider,
            String model,
            String effort,
            List<String> skills)
            implements RosterEvent {
        public AgentHired {
            skills = skills == null ? null : Collections.unmodifiableList(new ArrayList<>(skills));
        }
    }

    record AgentAssignmentsSuspended(AgentId agent, String reason) implements RosterEvent {}

    record AgentAssignmentsResumed(AgentId agent) implements RosterEvent {}

    /** The director changes the model or effort an agent's next sessions run with. */
    record AgentReconfigured(AgentId agent, String model, String effort) implements RosterEvent {}

    /** The work Floor42 gives the agent (ADR-0019); recorded with the hiring. */
    record AgentRoleAssigned(AgentId agent, dev.floor42.domain.WorkRole workRole) implements RosterEvent {}

    /** The agent leaves the company: no new work; its history stays in the journal. */
    record AgentDismissed(AgentId agent, String reason) implements RosterEvent {}

    /** The director stars the agent as a core member: "dismiss everyone except the core members" keeps it. */
    record AgentMarkedCore(AgentId agent) implements RosterEvent {}

    /** The director takes the star away: the agent is no longer a core member. */
    record AgentUnmarkedCore(AgentId agent) implements RosterEvent {}
}
