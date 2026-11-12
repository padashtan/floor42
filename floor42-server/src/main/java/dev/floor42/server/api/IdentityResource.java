package dev.floor42.server.api;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.iam.AgentClients;
import dev.floor42.server.iam.Delegations;
import dev.floor42.server.iam.IamSessions;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.iam.IamSettings.Settings;
import dev.floor42.server.iam.IamStatus;
import dev.floor42.server.iam.IdentityRefusals;
import dev.floor42.server.iam.IdpExchanges;
import dev.floor42.server.iam.IdpRefusedException;
import dev.floor42.server.iam.IdpUnavailableException;
import dev.floor42.server.iam.SecretVault;
import dev.floor42.server.journal.Journal;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * Agent identity (IAM mode): its health, its settings, and the agents' Keycloak clients. Settings take effect at the
 * next start. The two Keycloak secrets are write-only: they go into the vault and no answer, log or event repeats them.
 */
@Path("/api/iam")
@Produces(MediaType.APPLICATION_JSON)
public class IdentityResource {

    /** The vault entries the Keycloak client reads (KeycloakClient). */
    static final String DAEMON_ENTRY = "floor42-daemon";

    static final String API_ENTRY = "floor42-api";

    /** {@code daemonSecret} and {@code apiSecret}: when blank the stored secret stays; never returned. */
    public record IamSettingsRequest(
            boolean enabled, String issuer, int maxTokenHours, String daemonSecret, String apiSecret) {}

    public record IamSettingsAnswer(boolean restartNeeded) {}

    /** {@code code} is set when the daemon refused to start IAM mode (IAM_NEEDS_SANDBOX...). */
    public record IamStatusView(
            boolean enabled,
            IamStatus.Health health,
            String code,
            String reason,
            Instant lastCheck,
            int failedChecks,
            boolean restartNeeded,
            String issuer,
            int maxTokenHours,
            boolean savedEnabled) {}

    /** PUT /api/iam/secrets/{kind}: one client secret, written to the vault only; never returned. */
    public record IamSecretRequest(String secret) {}

    public record AgentIdentityView(
            String agent, String clientId, String serviceAccountId, boolean enabled, boolean revoked) {}

    /** What a mission's delegation is: metadata only, never a token. */
    public record DelegationView(
            String mission, Instant grantedAt, Instant expiresAt, List<String> resources, boolean revoked) {
        public DelegationView {
            resources = resources == null ? null : Collections.unmodifiableList(new ArrayList<>(resources));
        }
    }

    /** What the director is told before approving a plan in IAM mode; the UI shows its own translation of it. */
    public record ConsentView(String text, Instant expiresAt, List<String> resources) {
        public ConsentView {
            resources = resources == null ? null : Collections.unmodifiableList(new ArrayList<>(resources));
        }
    }

    /** A recent refusal of an agent token: its code, the jti when the signature held, the launch it named. */
    public record RefusalView(Instant at, String code, String jti, String launch) {}

    private final IamSettings settings;
    private final IamStatus status;
    private final AgentClients clients;
    private final SecretVault vault;
    private final Delegations delegations;
    private final IamSessions sessions;
    private final IdentityRefusals refusals;
    private final Journal journal;
    private final IdpExchanges exchanges;

    public IdentityResource(
            IamSettings settings,
            IamStatus status,
            AgentClients clients,
            SecretVault vault,
            Delegations delegations,
            IamSessions sessions,
            IdentityRefusals refusals,
            Journal journal,
            IdpExchanges exchanges) {
        this.settings = settings;
        this.status = status;
        this.clients = clients;
        this.vault = vault;
        this.delegations = delegations;
        this.sessions = sessions;
        this.refusals = refusals;
        this.journal = journal;
        this.exchanges = exchanges;
    }

    /**
     * The last exchanges between Floor42 and Keycloak, newest first: in memory, at most 300, lost at a restart; no
     * secret, token, password or code (IdpExchanges). Served whether IAM mode is up or not, so a failure shows too.
     */
    @GET
    @Path("/exchanges")
    public List<IdpExchanges.Exchange> exchanges() {
        return exchanges.list();
    }

    @GET
    @Path("/tokens")
    public List<IdentityTokens.TokenView> tokens() {
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        return IdentityTokens.list(sessions, journal);
    }

    @GET
    @Path("/refusals")
    public List<RefusalView> recentRefusals() {
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        return refusals.recent().stream()
                .map(r -> new RefusalView(r.at(), r.code(), r.jti(), r.launch()))
                .toList();
    }

    @GET
    @Path("/missions/{id}/delegation")
    public DelegationView delegation(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        return delegations
                .find(m)
                .map(d -> new DelegationView(
                        d.mission().toString(), d.grantedAt(), d.expiresAt(), d.resources(), d.revoked()))
                .orElseThrow(() -> RefusedException.notFound("NO_DELEGATION", "No delegation for " + id));
    }

    @GET
    @Path("/missions/{id}/consent")
    public ConsentView consent(
            @PathParam("id") String id,
            @Parameter(description = "Comma-separated resource names the plan approval will grant (optional).")
                    @QueryParam("resources")
                    String resources) {
        MissionId m = parse(() -> new MissionId(id));
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        List<String> names;
        try {
            names = Delegations.cleanResources(
                    resources == null || resources.isBlank() ? List.of() : List.of(resources.split(",")));
        } catch (IllegalArgumentException e) {
            throw RefusedException.invalid("INVALID_RESOURCES", e.getMessage(), e);
        }
        Instant until = Instant.now().plus(Delegations.LIFETIME);
        return new ConsentView(
                "The agents of " + m + " will act on Floor42 for you until " + until + " or until the mission ends, "
                        + (names.isEmpty() ? "with no external resource" : "reaching " + String.join(", ", names))
                        + ". Keycloak will ask for your password again to confirm.",
                until,
                names);
    }

    @POST
    @Path("/missions/{id}/delegation/revoke")
    public DelegationView revokeDelegation(@PathParam("id") String id) {
        MissionId m = parse(() -> new MissionId(id));
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        if (delegations.find(m).isEmpty()) throw RefusedException.notFound("NO_DELEGATION", "No delegation for " + id);
        delegations.revokeLocally(m);
        // Keycloak is told now when it answers; if not, the vault entry stays and the revoke is retried.
        delegations.revokeRemote(m);
        return delegation(id);
    }

    @GET
    @Path("/status")
    public IamStatusView status() {
        IamStatus.View v = status.view();
        Settings saved = settings.saved();
        return new IamStatusView(
                settings.enabled(),
                v.health(),
                v.code(),
                v.reason(),
                v.lastCheck(),
                v.failedChecks(),
                v.restartNeeded(),
                saved.issuer(),
                saved.maxTokenHours(),
                saved.enabled());
    }

    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public IamSettingsAnswer save(IamSettingsRequest r) {
        if (r == null) throw RefusedException.invalid("MISSING_FIELDS", "enabled and issuer are required");
        String issuer = r.issuer() == null ? "" : r.issuer().strip();
        if (!validIssuer(issuer))
            throw RefusedException.invalid(
                    "INVALID_ISSUER", "issuer must be an http(s) address, e.g. " + IamSettings.DEFAULT_ISSUER);
        if (r.daemonSecret() != null && !r.daemonSecret().isBlank())
            vault.put(DAEMON_ENTRY, r.daemonSecret().strip().getBytes(StandardCharsets.UTF_8));
        if (r.apiSecret() != null && !r.apiSecret().isBlank())
            vault.put(API_ENTRY, r.apiSecret().strip().getBytes(StandardCharsets.UTF_8));
        settings.save(new Settings(r.enabled(), issuer, r.maxTokenHours()));
        return new IamSettingsAnswer(settings.restartNeeded());
    }

    /**
     * Replaces the floor42-api ({@code api}) or floor42-daemon ({@code daemon}) secret in the vault, nothing else: the
     * settings stay as saved. The Keycloak client reads the vault on each call, so no restart is needed for it.
     */
    @PUT
    @Path("/secrets/{kind}")
    @Consumes(MediaType.APPLICATION_JSON)
    public IamSettingsAnswer replaceSecret(@PathParam("kind") String kind, IamSecretRequest r) {
        String entry = switch (kind) {
            case "api" -> API_ENTRY;
            case "daemon" -> DAEMON_ENTRY;
            default -> throw RefusedException.invalid("UNKNOWN_SECRET", "kind must be api or daemon");
        };
        if (r == null || r.secret() == null || r.secret().isBlank())
            throw RefusedException.invalid("MISSING_FIELDS", "secret is required");
        vault.put(entry, r.secret().strip().getBytes(StandardCharsets.UTF_8));
        return new IamSettingsAnswer(settings.restartNeeded());
    }

    @GET
    @Path("/agents")
    public List<AgentIdentityView> agents() {
        return live(() -> clients.views().stream()
                .map(v -> new AgentIdentityView(
                        v.agent().value(), v.clientId(), v.serviceAccountId(), v.enabled(), v.revoked()))
                .toList());
    }

    @POST
    @Path("/agents/{id}/rotate")
    public IamSettingsAnswer rotate(@PathParam("id") String id) {
        AgentId agent = known(id);
        return live(() -> {
            clients.rotate(agent);
            return new IamSettingsAnswer(false);
        });
    }

    @POST
    @Path("/agents/{id}/revoke")
    public IamSettingsAnswer revoke(@PathParam("id") String id) {
        AgentId agent = known(id);
        return live(() -> {
            clients.revoke(agent);
            return new IamSettingsAnswer(false);
        });
    }

    @POST
    @Path("/agents/{id}/restore")
    public IamSettingsAnswer restore(@PathParam("id") String id) {
        AgentId agent = known(id);
        return live(() -> {
            clients.enable(agent);
            return new IamSettingsAnswer(false);
        });
    }

    private AgentId known(String id) {
        AgentId agent = parse(() -> new AgentId(id));
        gate();
        if (!clients.onStaff(agent)) throw RefusedException.notFound("UNKNOWN_AGENT", "No agent " + id);
        return agent;
    }

    private void gate() {
        if (!settings.enabled()) throw RefusedException.conflict("IAM_OFF", "IAM mode is off");
        status.blocksSessions().ifPresent(why -> {
            throw RefusedException.unavailable("IDENTITY_UNAVAILABLE", why);
        });
    }

    /** Keycloak's failures become the documented answers; the messages hold no credential (KeycloakClient). */
    private <T> T live(Supplier<T> call) {
        gate();
        try {
            return call.get();
        } catch (IdpUnavailableException e) {
            throw RefusedException.unavailable("IDENTITY_UNAVAILABLE", e.getMessage(), e);
        } catch (IdpRefusedException e) {
            throw RefusedException.conflict("IDENTITY_REFUSED", e.getMessage(), e);
        }
    }

    private static boolean validIssuer(String s) {
        try {
            URI u = URI.create(s);
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            // Handled: not an address, the request is wrong
            return false;
        }
    }
}
