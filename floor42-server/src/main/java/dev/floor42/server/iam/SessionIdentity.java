package dev.floor42.server.iam;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.HookRegistry;
import dev.floor42.server.runtime.RuntimeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Where a session's identity token comes from (IAM mode): a Keycloak Token Exchange by the agent's own client on the
 * director's delegation (or sign-in, for planning), verified by Floor42 before Claude Code ever sees it. Simulated
 * sessions get a token of the simulated issuer instead. Any failure: the session does not start, no fallback.
 */
@ApplicationScoped
public class SessionIdentity {

    /** The ticket claim of a session that serves no ticket (a retrospective, a staffing review): the verifier needs one. */
    public static final TicketId NO_TICKET = new TicketId("T-0");

    /** The token and what the journal may know of it; {@code toString} never shows the token. */
    public record Minted(String token, String jti, String issuer, Instant expiresAt, Instant since) {
        /** {@code since}: when the checks that let this session start began; a revoke after it must bite. */
        public Minted(String token, String jti, String issuer, Instant expiresAt) {
            this(token, jti, issuer, expiresAt, Instant.now());
        }

        public RuntimeEvent.TokenIssued event() {
            return new RuntimeEvent.TokenIssued(issuer, expiresAt, jti);
        }

        @Override
        public String toString() {
            return "Minted[jti=" + jti + ", issuer=" + issuer + ", expiresAt=" + expiresAt + "]";
        }
    }

    private static final Duration SIMULATED_LIFE = Duration.ofHours(1);

    private final IamSettings settings;
    private final IamStatus status;
    private final SecretVault vault;
    private final KeycloakClient keycloak;
    private final Delegations delegations;
    private final AgentTokenVerifier verifier;
    private final SimulatedIssuer simulated;
    private final OfficeFacts office;
    private final AgentClients clients;

    @Inject
    SessionIdentity(
            IamSettings settings,
            IamStatus status,
            SecretVault vault,
            KeycloakClient keycloak,
            Delegations delegations,
            AgentTokenVerifier verifier,
            SimulatedIssuer simulated,
            OfficeFacts office,
            AgentClients clients) {
        this.settings = settings;
        this.status = status;
        this.vault = vault;
        this.keycloak = keycloak;
        this.delegations = delegations;
        this.verifier = verifier;
        this.simulated = simulated;
        this.office = office;
        this.clients = clients;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** What Floor42 itself expects the session's token to say, whatever the token claims. */
    public SessionClaims claimsOf(SessionSpec spec, String launch) {
        return new SessionClaims(
                launch, spec.agent(), spec.mission(), spec.ticket() == null ? NO_TICKET : spec.ticket());
    }

    /** Registers the session for hook calls: with its Keycloak token, or (IAM off, {@code minted} null) a random one. */
    public String register(HookRegistry hooks, Governor governor, SessionSpec spec, String launch, Minted minted) {
        return minted == null ? hooks.register(governor) : hooks.register(governor, claimsOf(spec, launch), minted);
    }

    /** The session's token; null when IAM mode is off. Throws {@link IdentityUnavailableException}: do not start. */
    public Minted mint(SessionSpec spec, String launch) {
        if (!settings.enabled()) return null;
        Instant since = Instant.now();
        status.blocksSessions().ifPresent(r -> {
            throw new IdentityUnavailableException(r);
        });
        if (clients.revoked().contains(spec.agent()))
            throw new IdentityUnavailableException("the agent's identity is revoked");
        boolean retro = isRetro(spec);
        boolean work = onDelegation(spec, retro);
        requireDelegation(spec, retro, work);
        SessionClaims claims = claimsOf(spec, launch);
        String jwt;
        try {
            jwt = keycloak.exchange(
                    KeycloakClient.clientId(spec.agent()),
                    vault.get(KeycloakClient.vaultName(spec.agent()))
                            .orElseThrow(() -> new IdentityUnavailableException("the agent has no Keycloak secret")),
                    subject(spec.mission(), work),
                    AgentTokenVerifier.AUDIENCE,
                    Map.of(
                            "floor42_launch", launch,
                            "floor42_mission", claims.mission().value(),
                            "floor42_ticket", claims.ticket().value()));
        } catch (IdpRefusedException | IdpUnavailableException e) {
            // Handled: Keycloak said no or is away; the session does not start and the mission is told why
            throw new IdentityUnavailableException("Keycloak gave no token: " + e.getMessage(), e);
        }
        return checked(jwt, claims, since);
    }

    /**
     * Work, and a retrospective, ride the delegation; the retrospective of a mission approved while IAM mode was off
     * has none and rides the sign-in as before.
     */
    private boolean onDelegation(SessionSpec spec, boolean retro) {
        return atWork(spec.mission())
                || (retro && delegations.find(spec.mission()).isPresent());
    }

    /** After completion the delegation serves the retrospective only (it is revoked when that has ended). */
    private void requireDelegation(SessionSpec spec, boolean retro, boolean work) {
        delegations.blocks(spec.mission(), work).ifPresent(r -> {
            throw new IdentityUnavailableException(r);
        });
        if (!retro
                && completed(spec.mission())
                && delegations.find(spec.mission()).isPresent())
            throw new IdentityUnavailableException(
                    "the mission is completed: only its retrospective may use the delegation");
    }

    /** The retrospective's own sessions carry its own ticket (ADR-0020). */
    private boolean isRetro(SessionSpec spec) {
        return spec.ticket() != null && office.isRetroTicket(spec.mission(), spec.ticket());
    }

    private boolean completed(MissionId mission) {
        return office.missionStatus(mission)
                .map(s -> s == MissionStatus.COMPLETED)
                .orElse(false);
    }

    /** The plan is approved and the mission lives: its agents work, and only a delegation may carry them. */
    private boolean atWork(MissionId mission) {
        return office.missionStatus(mission)
                .filter(x -> x == MissionStatus.IN_PROGRESS || x == MissionStatus.SUSPENDED)
                .isPresent();
    }

    /**
     * A work session or a retrospective (of a mission that has a delegation): the mission's delegation, never
     * anything else. Every other session (planning, committees, consultations, staffing: they serve no approved plan)
     * rides the director's live sign-in.
     */
    private String subject(MissionId mission, boolean work) {
        if (work) return delegations.subjectTokenFor(mission);
        return delegations
                .forPlanning()
                .orElseThrow(() -> new IdentityUnavailableException(
                        "waiting for the director's sign-in: a session before the plan is approved needs it"));
    }

    /** A token Floor42 would refuse is never handed to Claude Code. */
    private Minted checked(String jwt, SessionClaims claims, Instant since) {
        if (!(verifier.verify(jwt) instanceof Ok ok)
                || !ok.claims().equals(claims)
                || !AgentTokenVerifier.onlyOurAudience(ok.payload().path("aud")))
            throw new IdentityUnavailableException("Floor42 would refuse the token Keycloak gave");
        return new Minted(jwt, ok.jti(), ok.issuer(), ok.expiresAt(), since);
    }

    /** A simulated session's token (never accepted by a hook); empty when IAM mode is off. */
    public Optional<Minted> mintSimulated(SessionSpec spec) {
        if (!settings.enabled()) return Optional.empty();
        SessionClaims claims = claimsOf(spec, java.util.UUID.randomUUID().toString());
        String jwt = simulated.mint(claims, SIMULATED_LIFE);
        var payload = Jwts.parse(jwt).claims();
        return Optional.of(new Minted(
                jwt,
                payload.path("jti").asText(),
                SimulatedIssuer.ISSUER,
                Instant.ofEpochSecond(payload.path("exp").asLong())));
    }
}
