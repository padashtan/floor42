package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireDirector;
import static dev.floor42.domain.Rules.requireSystem;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent;
import dev.floor42.domain.event.SessionEvent.AgentRemarked;
import dev.floor42.domain.event.SessionEvent.AgentSessionEnded;
import dev.floor42.domain.event.SessionEvent.AgentSessionStarted;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import dev.floor42.domain.event.SessionEvent.MissionQuarantined;
import dev.floor42.domain.event.SessionEvent.RuntimeLineRecorded;
import dev.floor42.domain.event.SessionEvent.SessionContinuationRequested;
import dev.floor42.domain.event.SessionEvent.SessionContinued;
import dev.floor42.domain.event.SessionEvent.SessionRiskRaised;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The agents' sessions, as Floor42's runtime recorded them (never the agent), and the director's continuations. */
final class SessionLog {

    private static final String RUNTIME_ONLY = "Only the runtime records sessions";

    private final CompanyView view;
    private final WorkEligibility eligibility;
    private final Map<String, AgentSession> sessions = new LinkedHashMap<>();
    private final List<ContinuationRequest> continuations = new ArrayList<>();

    SessionLog(CompanyView view) {
        this.view = view;
        this.eligibility = new WorkEligibility(view);
    }

    List<AgentSession> sessions(MissionId mission) {
        return sessions.values().stream()
                .filter(s -> s.mission().equals(mission))
                .toList();
    }

    Collection<AgentSession> sessionsView() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    Optional<AgentSession> session(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    String modelOfSession(String session) {
        AgentSession s = sessions.get(session);
        return s == null ? null : s.model();
    }

    List<EventEnvelope> start(
            CommandContext ctx,
            String sessionId,
            TicketId ticketId,
            AgentId agentId,
            String model,
            String version,
            Double capUsd) {
        requireSystem(ctx, RUNTIME_ONLY);
        require(
                !sessions.containsKey(sessionId),
                Refusal.DUPLICATE_SESSION,
                "Session " + sessionId + " already recorded");
        Ticket t = view.existingTicket(ticketId);
        Mission m = view.missionFor(ctx, t.mission());
        require(!view.existingAgent(agentId).dismissed, Refusal.AGENT_DISMISSED, agentId + " has left the company");
        // An archived mission is over and put away (ADR-0054): nothing starts on it until the director restores it.
        require(!m.archived, Refusal.MISSION_ARCHIVED, m.id() + " is archived");
        boolean retro = eligibility.speaksInRetro(agentId, t);
        require(m.status == MissionStatus.IN_PROGRESS || retro, Refusal.MISSION_NOT_ACTIVE, m.id() + " is " + m.status);
        require(
                eligibility.worksOn(agentId, t) || retro,
                Refusal.NOT_ON_TICKET,
                agentId + " is neither working on nor reviewing " + ticketId);
        requireText(model, "model");
        return view.record(ctx, t.mission())
                .emit(new AgentSessionStarted(sessionId, t.mission(), ticketId, agentId, model, version, capUsd))
                .events();
    }

    List<EventEnvelope> remark(CommandContext ctx, String sessionId, String text) {
        AgentSession s = open(ctx, sessionId);
        if (text == null || text.isBlank()) return List.of();
        String said = text.strip().replaceAll("\\s+", " ");
        if (said.length() > SessionsFacade.REMARK_MAX) said = said.substring(0, SessionsFacade.REMARK_MAX - 1) + "…";
        return view.record(ctx, s.mission())
                .emit(new AgentRemarked(sessionId, s.agent(), s.ticket(), said))
                .events();
    }

    List<EventEnvelope> runtimeLine(CommandContext ctx, String sessionId, String kind, String content) {
        AgentSession s = open(ctx, sessionId);
        requireText(kind, "kind");
        String c = content == null ? "" : content;
        if (c.length() > SessionsFacade.RUNTIME_LINE_MAX) c = c.substring(0, SessionsFacade.RUNTIME_LINE_MAX - 1) + "…";
        return view.record(ctx, s.mission())
                .emit(new RuntimeLineRecorded(sessionId, s.agent(), s.ticket(), kind, c))
                .events();
    }

    List<EventEnvelope> tokenIssued(
            CommandContext ctx, String sessionId, String issuer, Instant expiresAt, String tokenId) {
        AgentSession s = open(ctx, sessionId);
        requireText(issuer, "issuer");
        requireText(tokenId, "tokenId");
        return view.record(ctx, s.mission())
                .emit(new AgentTokenIssued(
                        sessionId, s.agent(), s.mission(), s.ticket(), issuer, expiresAt.toString(), tokenId))
                .events();
    }

    List<EventEnvelope> riskRaised(
            CommandContext ctx,
            String sessionId,
            RiskLevel level,
            List<RiskReason> reasons,
            Instant windowStart,
            List<ActionId> actions) {
        AgentSession s = open(ctx, sessionId);
        require(!reasons.isEmpty(), Refusal.INVALID_TRANSITION, "a risk raise names its reasons");
        RiskLevel current = s.risk == null ? RiskLevel.NONE : s.risk.level();
        require(level.compareTo(current) > 0, Refusal.INVALID_TRANSITION, "risk only goes up: " + current);
        return view.record(ctx, s.mission())
                .emit(new SessionRiskRaised(
                        sessionId,
                        s.agent(),
                        s.mission(),
                        s.ticket(),
                        level.name(),
                        reasons.stream().map(RiskReason::name).toList(),
                        windowStart.toString(),
                        actions.stream().map(ActionId::value).toList()))
                .events();
    }

    /** The director's quarantine of a session at risk, recorded once with what each step did (even if none did). */
    List<EventEnvelope> quarantined(
            CommandContext ctx, String sessionId, boolean revoked, boolean suspended, boolean stopped) {
        requireDirector(ctx);
        AgentSession s = existing(sessionId);
        view.missionFor(ctx, s.mission());
        require(s.risk != null, Refusal.INVALID_TRANSITION, "no risk raised on " + sessionId);
        return view.record(ctx, s.mission())
                .emit(new MissionQuarantined(
                        sessionId,
                        s.agent(),
                        s.mission(),
                        s.ticket(),
                        s.risk.level().name(),
                        s.risk.reasons().stream().map(RiskReason::name).toList(),
                        revoked,
                        suspended,
                        stopped))
                .events();
    }

    /**
     * A process was launched for this ticket but never named its session (it died first, or the daemon did): a fact
     * recorded whatever the mission's state, so that its cap counts. Opened and ended at once, failed, with the cap as
     * its bound; the id says it is a launch.
     */
    List<EventEnvelope> unnamedLaunch(
            CommandContext ctx, String sessionId, TicketId ticketId, AgentId agentId, String model, double capUsd) {
        requireSystem(ctx, RUNTIME_ONLY);
        require(
                !sessions.containsKey(sessionId),
                Refusal.DUPLICATE_SESSION,
                "Session " + sessionId + " already recorded");
        Ticket t = view.existingTicket(ticketId);
        view.existingAgent(agentId);
        String named = model == null || model.isBlank() ? "not reported" : model;
        return view.record(ctx, t.mission())
                .emit(new AgentSessionStarted(sessionId, t.mission(), ticketId, agentId, named, null, capUsd))
                .emit(new AgentSessionEnded(
                        sessionId, true, "failed before naming its session", null, null, null, null, capUsd))
                .events();
    }

    List<EventEnvelope> end(CommandContext ctx, AgentSessionEnded ended) {
        AgentSession s = open(ctx, ended.session());
        require(validCost(ended.costUsdEstimated()), Refusal.INVALID_ARGUMENT, "costUsdEstimated must be finite, >= 0");
        require(validCost(ended.costUsdBound()), Refusal.INVALID_ARGUMENT, "costUsdBound must be finite, >= 0");
        // Neither figure reported: the session's cap still counts, as an upper bound.
        if (ended.costUsdEstimated() == null && ended.costUsdBound() == null && s.capUsd() != null) {
            ended = new AgentSessionEnded(
                    ended.session(),
                    ended.failed(),
                    ended.outcome(),
                    ended.turns(),
                    ended.inputTokensProvided(),
                    ended.outputTokensProvided(),
                    null,
                    s.capUsd());
        }
        return view.record(ctx, s.mission()).emit(ended).events();
    }

    private static boolean validCost(Double usd) {
        return usd == null || (Double.isFinite(usd) && usd >= 0);
    }

    /** A session the runtime may still write to: recorded, of the command's origin, not ended. */
    private AgentSession open(CommandContext ctx, String sessionId) {
        requireSystem(ctx, RUNTIME_ONLY);
        AgentSession s = existing(sessionId);
        view.missionFor(ctx, s.mission());
        require(s.isOpen(), Refusal.SESSION_CLOSED, sessionId + " already ended");
        return s;
    }

    private AgentSession existing(String sessionId) {
        AgentSession s = sessions.get(sessionId);
        require(s != null, Refusal.UNKNOWN_SESSION, "No session " + sessionId);
        return s;
    }

    List<EventEnvelope> requestContinuation(CommandContext ctx, String sessionId) {
        requireDirector(ctx);
        AgentSession s = existing(sessionId);
        Mission m = view.missionFor(ctx, s.mission());
        require(m.origin() == Origin.REAL, Refusal.SIMULATED_MISSION, "A simulated session cannot be continued");
        require(!s.isOpen(), Refusal.SESSION_OPEN, sessionId + " is still running");
        require(m.status == MissionStatus.IN_PROGRESS, Refusal.MISSION_NOT_ACTIVE, m.id() + " is " + m.status);
        Ticket t = view.existingTicket(s.ticket());
        require(
                t.status == TicketStatus.IN_PROGRESS && s.agent().equals(t.assignee),
                Refusal.NOT_CONTINUABLE,
                sessionId + " is not the work in progress of " + s.agent() + " on " + t.id());
        require(
                latest(s.agent(), s.ticket()).map(x -> x.id().equals(sessionId)).orElse(false),
                Refusal.NOT_LATEST_SESSION,
                "A later session of " + s.agent() + " on " + t.id() + " exists");
        return view.record(ctx, m.id())
                .emit(new SessionContinuationRequested(m.id(), t.id(), s.agent(), sessionId))
                .events();
    }

    /** The session to continue, while no session of the agent has started on the ticket since the request. */
    Optional<String> continuationRequested(MissionId mission, TicketId ticket, AgentId agent) {
        Instant since = latest(agent, ticket).map(AgentSession::startedAt).orElse(Instant.MIN);
        return continuations.stream()
                .filter(r -> r.mission().equals(mission)
                        && r.ticket().equals(ticket)
                        && r.agent().equals(agent))
                .filter(r -> r.at().isAfter(since))
                .reduce((a, b) -> b)
                .map(ContinuationRequest::session);
    }

    List<EventEnvelope> recordContinuation(CommandContext ctx, String sessionId, String from) {
        requireSystem(ctx, RUNTIME_ONLY);
        AgentSession s = sessions.get(sessionId);
        AgentSession earlier = sessions.get(from);
        require(s != null, Refusal.UNKNOWN_SESSION, "No session " + sessionId);
        require(earlier != null, Refusal.UNKNOWN_SESSION, "No session " + from);
        view.missionFor(ctx, s.mission());
        require(
                s.agent().equals(earlier.agent()) && s.ticket().equals(earlier.ticket()) && !sessionId.equals(from),
                Refusal.NOT_CONTINUABLE,
                sessionId + " cannot continue " + from);
        return view.record(ctx, s.mission())
                .emit(new SessionContinued(sessionId, from))
                .events();
    }

    private Optional<AgentSession> latest(AgentId agent, TicketId ticket) {
        return sessions.values().stream()
                .filter(x -> x.agent().equals(agent) && x.ticket().equals(ticket))
                .reduce((a, b) -> b);
    }

    /** The only way this area's state changes. */
    void apply(SessionEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case AgentSessionStarted e -> on(e, at);
            case AgentRemarked e -> on(e);
            case RuntimeLineRecorded e -> on(e);
            case AgentSessionEnded e -> on(e, at);
            case SessionContinuationRequested e -> on(e, at);
            case SessionContinued e -> on(e);
            case AgentTokenIssued e -> on(e);
            case SessionRiskRaised e -> on(e, at);
            case MissionQuarantined e -> on(e);
        }
    }

    void on(AgentSessionStarted e, Instant at) {
        AgentSession s =
                new AgentSession(e.session(), e.mission(), e.ticket(), e.agent(), e.model(), e.runtimeVersion(), at);
        s.capUsd = e.capUsd();
        sessions.put(e.session(), s);
    }

    void on(AgentRemarked e) {
        // Words only: nothing in the company's state changes.
    }

    void on(RuntimeLineRecorded e) {
        // For the record: nothing in the company's state changes.
    }

    /** The session that ended; the actions it left pending are the action log's. */
    AgentSession on(AgentSessionEnded e, Instant at) {
        AgentSession s = sessions.get(e.session());
        s.endedAt = at;
        s.failed = e.failed();
        s.outcome = e.outcome();
        s.turns = e.turns();
        s.inputTokensProvided = e.inputTokensProvided();
        s.outputTokensProvided = e.outputTokensProvided();
        s.costUsdEstimated = e.costUsdEstimated();
        s.costUsdBound = e.costUsdBound();
        return s;
    }

    void on(SessionContinuationRequested e, Instant at) {
        continuations.add(new ContinuationRequest(e.mission(), e.ticket(), e.agent(), e.session(), at));
    }

    void on(AgentTokenIssued e) {
        AgentSession s = sessions.get(e.session());
        s.lastTokenId = e.tokenId();
        s.tokenIssuer = e.issuer();
        s.tokenExpiresAt = Instant.parse(e.expiresAt());
    }

    void on(SessionRiskRaised e, Instant at) {
        List<RiskReason> reasons = e.reasons().stream().map(RiskReason::valueOf).toList();
        sessions.get(e.session()).risk = new AgentSession.Risk(RiskLevel.valueOf(e.level()), reasons, at);
    }

    void on(MissionQuarantined e) {
        // For the record: the suspension and the revoke are the mission's and the delegation's own facts.
    }

    void on(SessionContinued e) {
        sessions.get(e.session()).continuedFrom = e.from();
    }
}
