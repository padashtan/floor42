package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.asDirector;
import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Progress;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.runtime.RunningSessions;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

/**
 * The director's quarantine of a session that raised a risk (ADR-0066): revoke the mission's delegation, suspend the
 * mission, stop the session, in that order, each through the path that already exists. Only ever on the director's
 * click; nothing in Floor42 calls it. Each accepted click is journaled once as {@code MissionQuarantined}, with the
 * steps that did something. Resuming is the existing resume route. Known gap, decided for v1 (D1, ADR-0066): in IAM
 * mode a quarantined or suspended mission whose plan is already approved cannot be re-delegated, so it stays blocked
 * on "delegation revoked"; there is no route for it (docs/iam-revocation.md).
 */
@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class QuarantineResource {

    private static final Logger LOG = Logger.getLogger(QuarantineResource.class);

    public record QuarantineRequest(String agent) {}

    /** Which steps did something; a second click answers all false. */
    public record QuarantineView(String mission, String session, boolean revoked, boolean suspended, boolean stopped) {}

    @Inject
    OfficeService office;

    @Inject
    IamSettings iam;

    @Inject
    Delegations delegations;

    @Inject
    RunningSessions running;

    @POST
    @Path("/missions/{id}/quarantine")
    public QuarantineView quarantine(@PathParam("id") String id, QuarantineRequest body) {
        MissionId m = parse(() -> new MissionId(id));
        AgentId agent = parse(() -> new AgentId(body == null ? null : body.agent()));
        if (office.query(c -> c.missions().find(m)).isEmpty()) throw RefusedException.notFound("NO_MISSION", id);
        AgentSession s = office.query(c ->
                Progress.raised(c.missions().find(m).orElseThrow(), c.sessions().of(m)).stream()
                        .filter(x -> x.agent().equals(agent))
                        .findFirst()
                        .orElse(null));
        if (s == null) throw RefusedException.conflict("NO_RISK", "No new risk of " + agent + " in " + id);
        String codes = String.join(
                ", ", s.risk().orElseThrow().reasons().stream().map(Enum::name).toList());
        boolean revoked = false;
        boolean suspended = false;
        boolean stopped = false;
        try {
            revoked = revoke(m);
            suspended = suspend(m, "quarantine of session " + s.id() + ": " + codes);
        } finally {
            // Whatever failed above, the process still stops: it is the step that ends the harm.
            stopped = stop(m, agent);
            record(m, s.id(), revoked, suspended, stopped);
        }
        return new QuarantineView(id, s.id(), revoked, suspended, stopped);
    }

    /** One journal line per accepted quarantine, with what each step did (ADR-0066); never fails the response. */
    private void record(MissionId m, String session, boolean revoked, boolean suspended, boolean stopped) {
        try {
            office.execute(c -> c.sessions().quarantined(asDirector(office, m), session, revoked, suspended, stopped));
        } catch (RuntimeException e) {
            // Handled: the steps are done and the journal line is lost; the log says so for the director.
            LOG.warnf(
                    "Quarantine of session %s: not journaled (%s)",
                    session, e.getClass().getSimpleName());
        }
    }

    private boolean stop(MissionId m, AgentId agent) {
        return running.stop(agent, m, "the director quarantined the session");
    }

    private boolean revoke(MissionId m) {
        if (!iam.enabled() || delegations.find(m).filter(d -> !d.revoked()).isEmpty()) return false;
        delegations.revokeLocally(m);
        try {
            delegations.revokeRemote(m);
        } catch (RuntimeException e) {
            // Handled: the local revoke already refuses every token; Keycloak is told again by the retry at start.
            LOG.warnf(
                    "Quarantine of %s: Keycloak could not be told yet (%s)",
                    m, e.getClass().getSimpleName());
        }
        return true;
    }

    private boolean suspend(MissionId m, String reason) {
        Mission mission = office.query(c -> c.missions().find(m).orElseThrow());
        if (mission.status() != MissionStatus.IN_PROGRESS) return false;
        try {
            office.execute(c -> c.missions().suspend(asDirector(office, m), m, reason));
            return true;
        } catch (DomainException e) {
            // Handled: the mission moved on between the look and the command; the session is stopped all the same.
            LOG.warnf("Quarantine of %s: not suspended (%s)", m, e.code());
            return false;
        }
    }
}
