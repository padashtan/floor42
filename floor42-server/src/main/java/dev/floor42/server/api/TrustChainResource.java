package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.SessionEvent.AgentTokenIssued;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamSessions;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.iam.IdentityAudit;
import dev.floor42.server.iam.IdentityAudit.TrustChainView;
import dev.floor42.server.iam.IdentityRefusals;
import dev.floor42.server.journal.Journal;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;

/**
 * A session's trust chain, read back from the journal. Read-only. (A class-level path of its own: ContextResource owns
 * the {@code /api/sessions} prefix.)
 */
@Path("/api/sessions/{launch}/trust-chain")
public class TrustChainResource {

    private final IamSettings settings;
    private final IamSessions sessions;
    private final IdentityRefusals refusals;
    private final Delegations delegations;
    private final Journal journal;

    public TrustChainResource(
            IamSettings settings,
            IamSessions sessions,
            IdentityRefusals refusals,
            Delegations delegations,
            Journal journal) {
        this.settings = settings;
        this.sessions = sessions;
        this.refusals = refusals;
        this.delegations = delegations;
        this.journal = journal;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public TrustChainView trustChain(@PathParam("launch") String launch) {
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        List<EventEnvelope> events = journal.readAll();
        Optional<MissionId> mission = events.stream()
                .map(EventEnvelope::event)
                .filter(ev -> ev instanceof AgentTokenIssued t && t.session().equals(launch))
                .map(ev -> ((AgentTokenIssued) ev).mission())
                .findFirst();
        TrustChainView chain = IdentityAudit.chain(
                launch, events, mission.flatMap(delegations::find), sessions.boundPid(launch), refusals.recent());
        if (chain.links().isEmpty()) throw RefusedException.notFound("UNKNOWN_SESSION", "No identity for " + launch);
        return chain;
    }
}
