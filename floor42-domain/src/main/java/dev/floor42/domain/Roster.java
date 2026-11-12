package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireReal;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RosterEvent;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsResumed;
import dev.floor42.domain.event.RosterEvent.AgentAssignmentsSuspended;
import dev.floor42.domain.event.RosterEvent.AgentDismissed;
import dev.floor42.domain.event.RosterEvent.AgentHired;
import dev.floor42.domain.event.RosterEvent.AgentMarkedCore;
import dev.floor42.domain.event.RosterEvent.AgentReconfigured;
import dev.floor42.domain.event.RosterEvent.AgentRoleAssigned;
import dev.floor42.domain.event.RosterEvent.AgentUnmarkedCore;
import dev.floor42.domain.event.TicketEvent.TicketReleased;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The staff: who works here, in which role, with which model, and who left. */
final class Roster {

    private final CompanyView view;
    private final CompanyQueries queries;
    private final Map<AgentId, Agent> agents = new LinkedHashMap<>();

    Roster(CompanyView view) {
        this.view = view;
        this.queries = new CompanyQueries(view);
    }

    Collection<Agent> agents() {
        return List.copyOf(agents.values());
    }

    Collection<Agent> agentsView() {
        return Collections.unmodifiableCollection(agents.values());
    }

    Optional<Agent> agent(AgentId id) {
        return Optional.ofNullable(agents.get(id));
    }

    Agent existing(AgentId id) {
        Agent a = agents.get(id);
        require(a != null, Refusal.UNKNOWN_AGENT, "No agent " + id);
        return a;
    }

    List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            String provider,
            String model,
            String effort,
            List<String> skills) {
        requireDirector(ctx);
        require(!agents.containsKey(id), Refusal.DUPLICATE_AGENT, "Agent " + id + " already exists");
        requireText(displayName, "displayName");
        requireText(role, "role");
        requireText(provider, "provider");
        requireText(model, "model");
        return view.record(ctx, null)
                .emit(new AgentHired(id, displayName, role, provider, model, effort, skills))
                .events();
    }

    List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            WorkRole workRole,
            String model,
            String effort) {
        require(workRole != null, Refusal.INVALID_ARGUMENT, "workRole is required");
        List<EventEnvelope> events =
                new ArrayList<>(hire(ctx, id, displayName, role, "anthropic", model, effort, List.of()));
        events.addAll(
                view.record(ctx, null).emit(new AgentRoleAssigned(id, workRole)).events());
        return events;
    }

    List<EventEnvelope> dismiss(CommandContext ctx, AgentId id, String reason) {
        requireDirector(ctx);
        Agent agent = existing(id);
        require(!agent.dismissed, Refusal.ALREADY_DISMISSED, id + " has already left");
        requireText(reason, "reason");
        require(
                !queries.atWork(id),
                Refusal.AGENT_BUSY,
                id + " is at work right now (a session is open); dismiss it once that session has ended");
        Recorder r = view.record(ctx, null);
        queries.handedBackOnDismissal(id)
                .forEach(t -> r.on(view.existingTicket(t).mission()).emit(new TicketReleased(t, id)));
        return r.emit(new AgentDismissed(id, reason.strip())).events();
    }

    List<EventEnvelope> suspend(CommandContext ctx, AgentId id, String reason) {
        requireDirector(ctx);
        require(!existing(id).suspended, Refusal.ALREADY_SUSPENDED, id + " is already suspended");
        return view.record(ctx, null)
                .emit(new AgentAssignmentsSuspended(id, reason))
                .events();
    }

    List<EventEnvelope> resume(CommandContext ctx, AgentId id) {
        requireDirector(ctx);
        require(existing(id).suspended, Refusal.NOT_SUSPENDED, id + " is not suspended");
        return view.record(ctx, null).emit(new AgentAssignmentsResumed(id)).events();
    }

    List<EventEnvelope> reconfigure(CommandContext ctx, AgentId id, String model, String effort) {
        requireDirector(ctx);
        Agent agent = existing(id);
        requireText(model, "model");
        String e = Effort.parse(effort).map(Effort::value).orElse(null);
        if (model.strip().equals(agent.model()) && Objects.equals(e, agent.effort())) return List.of();
        return view.record(ctx, null)
                .emit(new AgentReconfigured(id, model.strip(), e))
                .events();
    }

    List<EventEnvelope> markCore(CommandContext ctx, AgentId id) {
        return star(ctx, id, new AgentMarkedCore(id));
    }

    List<EventEnvelope> unmarkCore(CommandContext ctx, AgentId id) {
        return star(ctx, id, new AgentUnmarkedCore(id));
    }

    /** A real decision of the director only; asking for the state the agent is already in records nothing. */
    private List<EventEnvelope> star(CommandContext ctx, AgentId id, DomainEvent change) {
        requireDirector(ctx);
        requireReal(ctx, "INVALID_ARGUMENT", "Core members are the director's real decision");
        Agent agent = existing(id);
        require(!agent.dismissed, Refusal.AGENT_DISMISSED, id + " has left the company");
        if (agent.core == (change instanceof AgentMarkedCore)) return List.of();
        return view.record(ctx, null).emit(change).events();
    }

    /** The only way this area's state changes. */
    void apply(RosterEvent event, EventEnvelope envelope) {
        switch (event) {
            case AgentHired e -> on(e);
            case AgentAssignmentsSuspended e -> on(e);
            case AgentAssignmentsResumed e -> on(e);
            case AgentReconfigured e -> on(e);
            case AgentRoleAssigned e -> on(e);
            case AgentDismissed e -> on(e);
            case AgentMarkedCore e -> on(e);
            case AgentUnmarkedCore e -> on(e);
        }
    }

    void on(AgentHired e) {
        agents.put(
                e.agent(),
                new Agent(e.agent(), e.displayName(), e.role(), e.provider(), e.model(), e.effort(), e.skills()));
    }

    void on(AgentAssignmentsSuspended e) {
        agents.get(e.agent()).suspended = true;
    }

    void on(AgentAssignmentsResumed e) {
        agents.get(e.agent()).suspended = false;
    }

    void on(AgentReconfigured e) {
        Agent a = agents.get(e.agent());
        a.model = e.model();
        a.effort = e.effort();
    }

    void on(AgentRoleAssigned e) {
        agents.get(e.agent()).workRole = e.workRole();
    }

    void on(AgentDismissed e) {
        agents.get(e.agent()).dismissed = true;
    }

    void on(AgentMarkedCore e) {
        agents.get(e.agent()).core = true;
    }

    void on(AgentUnmarkedCore e) {
        agents.get(e.agent()).core = false;
    }
}
