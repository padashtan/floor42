package dev.floor42.server.api.event;

import static dev.floor42.server.api.event.EventLine.line;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.Company;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
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
import java.util.ArrayList;
import java.util.List;

/** Agent sessions' lines, as the runtime recorded them, and their continuations. */
final class SessionRenderer implements EventRenderer<SessionEvent> {

    @Override
    public EventLine render(Company c, SessionEvent event) {
        return switch (event) {
            case AgentSessionStarted e ->
                session(e.session(), e.agent(), e.ticket())
                        .param("mission", e.mission())
                        .param("model", e.model())
                        .param("runtimeVersion", e.runtimeVersion())
                        .build();
            case AgentRemarked e ->
                session(e.session(), e.agent(), e.ticket())
                        .param("text", e.text())
                        .build();
            case RuntimeLineRecorded e ->
                session(e.session(), e.agent(), e.ticket())
                        .param("kind", e.kind())
                        .param("content", e.content())
                        .build();
            case AgentSessionEnded e -> simulated(c, e.session()) ? endedSimulated(e) : ended(e);
            case SessionContinuationRequested e ->
                session(e.session(), e.agent(), e.ticket())
                        .param("mission", e.mission())
                        .build();
            case AgentTokenIssued e -> token(c, e);
            case SessionRiskRaised e -> risk(e);
            case MissionQuarantined e -> quarantined(e);
            case SessionContinued e ->
                line().param("session", e.session()).param("from", e.from()).build();
        };
    }

    /** Codes only: the reasons are enum names, never what the agent wrote or read. */
    private static EventLine risk(SessionRiskRaised e) {
        return session(e.session(), e.agent(), e.ticket())
                .param("level", e.level())
                .param("reasons", String.join(", ", e.reasons()))
                .build();
    }

    /** Codes only: the steps that did something, as upper-case words like the reasons; NONE when none did. */
    private static EventLine quarantined(MissionQuarantined e) {
        List<String> done = new ArrayList<>();
        if (e.revoked()) done.add("REVOKED");
        if (e.suspended()) done.add("SUSPENDED");
        if (e.stopped()) done.add("STOPPED");
        return session(e.session(), e.agent(), e.ticket())
                .param("level", e.level())
                .param("reasons", String.join(", ", e.reasons()))
                .param("done", done.isEmpty() ? "NONE" : String.join(", ", done))
                .build();
    }

    /** The token itself is never in the event: its id, who issued it and when it stops. */
    private static EventLine token(Company c, AgentTokenIssued e) {
        return session(simulated(c, e.session()) ? "simulated" : null, e.session(), e.agent(), e.ticket())
                .param("tokenId", e.tokenId())
                .param("issuer", e.issuer())
                .param("expiresAt", e.expiresAt())
                .build();
    }

    /** The cost is the runtime's own estimate, written once for every language; absent when it gave none. */
    private static EventLine ended(AgentSessionEnded e) {
        Double cost = e.costUsdEstimated();
        EventLine.Builder line = line((e.failed() ? "failed" : "ended") + (cost == null ? "" : ".cost"))
                .param("session", e.session())
                .param("short", Lookups.shortId(e.session()))
                .param("failed", e.failed())
                .param("outcome", e.outcome())
                .param("turns", e.turns())
                .param("inputTokensProvided", e.inputTokensProvided())
                .param("outputTokensProvided", e.outputTokensProvided())
                .param("costUsdEstimated", cost);
        if (cost != null) line.param("cost", Lookups.usd(cost));
        return line.build();
    }

    /** A session of a simulated mission: no model was called, so no cost, whatever the recording carried. */
    private static boolean simulated(Company c, String session) {
        return c.sessions()
                .find(session)
                .flatMap(s -> c.missions().find(s.mission()))
                .map(m -> m.origin() != Origin.REAL)
                .orElse(false);
    }

    private static EventLine endedSimulated(AgentSessionEnded e) {
        return line((e.failed() ? "failed" : "ended") + ".simulated")
                .param("session", e.session())
                .param("short", Lookups.shortId(e.session()))
                .param("failed", e.failed())
                .param("outcome", e.outcome())
                .param("turns", e.turns())
                .build();
    }

    /** A line about one agent's session on a ticket: the scene points at both. */
    private static EventLine.Builder session(String session, AgentId agent, TicketId ticket) {
        return session(null, session, agent, ticket);
    }

    private static EventLine.Builder session(String variant, String session, AgentId agent, TicketId ticket) {
        return (variant == null ? line() : line(variant))
                .agent(agent)
                .ticket(ticket)
                .param("session", session)
                .param("agent", agent)
                .param("ticket", ticket);
    }
}
