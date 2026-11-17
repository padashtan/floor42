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
import dev.floor42.domain.event.RosterEvent.AgentTrustChanged;
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
        return hire(ctx, id, displayName, role, provider, model, effort, skills, true);
    }

    /** {@code probation} false for staff Floor42 seeds on a fresh install (planner, chairs): they start STANDARD. */
    List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            String provider,
            String model,
            String effort,
            List<String> skills,
            boolean probation) {
        requireDirector(ctx);
        require(!agents.containsKey(id), Refusal.DUPLICATE_AGENT, "Agent " + id + " already exists");
        requireText(displayName, "displayName");
        requireText(role, "role");
        requireText(provider, "provider");
        requireText(model, "model");
        Recorder r =
                view.record(ctx, null).emit(new AgentHired(id, displayName, role, provider, model, effort, skills));
        // Staff hired from now on start on probation (ADR-0082); a simulation has no real calls to cap.
        if (probation && ctx.origin() == Origin.REAL) {
            String hired = r.events().getLast().eventId();
            r.as(Actor.system())
                    .emit(new AgentTrustChanged(id, TrustLevel.STANDARD, TrustLevel.PROBATION, TrustCause.HIRE, hired));
        }
        return r.events();
    }

    /** The director narrows an agent's level by hand: restricting never needs proof, so no step-up. */
    List<EventEnvelope> lowerTrust(CommandContext ctx, AgentId id, TrustLevel to) {
        requireDirector(ctx);
        Agent agent = existing(id);
        require(!agent.dismissed, Refusal.AGENT_DISMISSED, id + " has left the company");
        require(
                to != null && to.compareTo(agent.trust) < 0,
                Refusal.INVALID_TRANSITION,
                id + " is already " + agent.trust + ": a hand change only narrows");
        return view.record(ctx, null)
                .emit(new AgentTrustChanged(id, agent.trust, to, TrustCause.DIRECTOR, null))
                .events();
    }

    /**
     * The director widens an agent's level. {@code stepUp} is the proof N3's mechanism gives (a fresh strong sign-in
     * and, with four eyes on, the second signature); without it the raise is refused here, in the domain.
     */
    List<EventEnvelope> raiseTrust(CommandContext ctx, AgentId id, TrustLevel to, boolean stepUp) {
        requireDirector(ctx);
        Agent agent = existing(id);
        require(!agent.dismissed, Refusal.AGENT_DISMISSED, id + " has left the company");
        require(
                to != null && to.compareTo(agent.trust) > 0,
                Refusal.INVALID_TRANSITION,
                id + " is already " + agent.trust + ": a raise widens");
        require(
                stepUp,
                Refusal.TRUST_RAISE_NEEDS_STEP_UP,
                "Widening an agent's rights needs a fresh strong sign-in (and a second signature when four eyes is on)");
        return view.record(ctx, null)
                .emit(new AgentTrustChanged(id, agent.trust, to, TrustCause.DIRECTOR, null))
                .events();
    }

    /**
     * An incident, a risk raise or a quarantine of this agent's session narrows its level by one, in the command that
     * records the cause (same events list). A real fact only: a simulation lowers nothing. Nothing below SHADOW.
     */
    static void narrow(
            CompanyView view, Recorder r, CommandContext ctx, AgentId id, TrustCause cause, String causeEvent) {
        Agent a = view.agent(id).orElse(null);
        if (ctx.origin() != Origin.REAL || a == null || a.dismissed || a.trust == TrustLevel.SHADOW) return;
        r.as(Actor.system()).emit(new AgentTrustChanged(id, a.trust, a.trust.lowered(), cause, causeEvent));
    }

    /** The director accepted a patch of this agent: one more toward a proposal (never an agent's peer review). */
    void patchAccepted(AgentId id, EventEnvelope envelope) {
        Agent a = agents.get(id);
        if (a != null && envelope.origin() == Origin.REAL && envelope.actor().isDirector()) {
            a.acceptedSinceTrustChange++;
        }
    }

    /** An incident starts the count again, even from SHADOW where there is nothing left to take away. */
    void incidentRaised(AgentId id, EventEnvelope envelope) {
        Agent a = agents.get(id);
        if (a != null && envelope.origin() == Origin.REAL) a.acceptedSinceTrustChange = 0;
    }

    List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            WorkRole workRole,
            String model,
            String effort) {
        return hire(ctx, id, displayName, role, workRole, model, effort, true);
    }

    List<EventEnvelope> hire(
            CommandContext ctx,
            AgentId id,
            String displayName,
            String role,
            WorkRole workRole,
            String model,
            String effort,
            boolean probation) {
        require(workRole != null, Refusal.INVALID_ARGUMENT, "workRole is required");
        List<EventEnvelope> events =
                new ArrayList<>(hire(ctx, id, displayName, role, "anthropic", model, effort, List.of(), probation));
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
            case AgentTrustChanged e -> on(e, envelope);
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

    void on(AgentTrustChanged e, EventEnvelope envelope) {
        Agent a = agents.get(e.agent());
        a.trust = e.to();
        a.trustSince = envelope.occurredAt();
        a.trustCause = e.cause();
        a.trustCauseEvent = e.causeEvent();
        a.acceptedSinceTrustChange = 0;
    }
}
