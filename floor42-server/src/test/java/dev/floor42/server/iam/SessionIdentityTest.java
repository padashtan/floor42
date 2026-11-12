package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.AgentTokenVerifier.Refused;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.security.SessionRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a session's token comes from: never without the right proof (the mission's delegation for work, the director's
 * sign-in before the plan), never when identity is blocked or revoked, and never a token Floor42 would refuse.
 */
class SessionIdentityTest {

    private static final AgentId ADA = new AgentId("ada");
    private static final MissionId M = new MissionId("M-1");
    private static final TicketId T = new TicketId("T-4");
    private static final TicketId RETRO = new TicketId("T-9");

    @TempDir
    Path dir;

    FakeKeycloak kc;
    KeycloakClient keycloakClient;
    SecretVault vault;
    IamSettings settings;
    IamStatus status;
    AgentClients clients;
    Delegations delegations;
    DirectorTokens directorTokens;
    SessionRegistry registry;
    AgentTokenVerifier verifier;
    SessionIdentity identity;
    boolean iamOn = true;
    final Map<MissionId, MissionStatus> statuses = new HashMap<>();
    final Set<TicketId> retroTickets = new HashSet<>(Set.of(RETRO));

    @BeforeEach
    void start() throws Exception {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-daemon", "daemon-secret-0123");
        kc.registerClient("floor42-api", "api-secret-4567");
        kc.registerClient("floor42-desk", null);
        vault = new SecretVault(dir);
        vault.put("floor42-daemon", "daemon-secret-0123".getBytes(UTF_8));
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        var json = new ObjectMapper();
        settings =
                new IamSettings(dir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), json) {
                    @Override
                    public boolean enabled() {
                        return iamOn;
                    }
                };
        var keycloak = new KeycloakClient(kc.issuer().toString(), vault, json);
        keycloakClient = keycloak;
        clients = new AgentClients(keycloak, settings, () -> List.of(ADA));
        var jwks = new Jwks(
                URI.create(kc.issuer() + "/protocol/openid-connect/certs"),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        SocketOwners owners = new SocketOwners() {
            public OptionalLong owner(InetSocketAddress client, InetSocketAddress server) {
                return OptionalLong.empty();
            }

            public boolean supported() {
                return true;
            }
        };
        status = new IamStatus(settings, vault, owners, jwks, clients, keycloak, () -> true);
        status.start();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
        registry = new SessionRegistry();
        for (String f : new String[] {"idleHours", "absoluteHours"}) {
            var field = SessionRegistry.class.getDeclaredField(f);
            field.setAccessible(true);
            field.setLong(registry, 24);
        }
        directorTokens = new DirectorTokens();
        directorTokens.sessions = registry;
        directorTokens.keycloak = keycloak;
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                () -> iamOn,
                status::blocksSessions,
                directorTokens,
                json);
        verifier = new AgentTokenVerifier(kc.issuer(), "floor42-api", jwks, Clock.systemUTC());
        OfficeFacts office = new OfficeFacts() {
            public java.util.Collection<AgentId> activeStaff() {
                return List.of(ADA);
            }

            public Optional<MissionStatus> missionStatus(MissionId mission) {
                return Optional.ofNullable(statuses.get(mission));
            }

            public boolean isRetroTicket(MissionId mission, TicketId ticket) {
                return retroTickets.contains(ticket);
            }

            public void breakGlassSignIn(String session) {}
        };
        identity = new SessionIdentity(
                settings, status, vault, keycloak, delegations, verifier, new SimulatedIssuer(), office, clients);
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private SessionSpec spec(TicketId ticket) {
        return new SessionSpec(M, ticket, ADA, Origin.REAL, "wd", "brief", null, null, List.of(), null);
    }

    private void directorSignedIn() {
        String id = registry.openBrowser("ua").session().id();
        directorTokens.put(id, keycloakClient.refresh(kc.refreshTokenFor("director", "floor42-desk"), "floor42-desk"));
    }

    private void delegate() {
        delegations.grant(
                M, new Tokens("a", kc.refreshTokenFor("director", "floor42-desk"), null, Instant.now()), List.of());
    }

    private String launch() {
        return java.util.UUID.randomUUID().toString();
    }

    @Test
    void withIamOffThereIsNoTokenAndNoSimulatedOne() {
        iamOn = false;
        assertThat(identity.mint(spec(T), launch())).isNull();
        assertThat(identity.mintSimulated(spec(T))).isEmpty();
        assertThat(identity.enabled()).isFalse();
    }

    @Test
    void aPlanningSessionRidesTheDirectorsSignInAndGetsAVerifiedToken() {
        directorSignedIn();
        statuses.put(M, MissionStatus.PLANNING);
        String launch = launch();
        Instant before = Instant.now();
        Minted m = identity.mint(spec(T), launch);
        Ok ok = (Ok) verifier.verify(m.token());
        assertThat(ok.claims()).isEqualTo(new SessionClaims(launch, ADA, M, T));
        assertThat(m.jti()).isEqualTo(ok.jti());
        assertThat(m.issuer()).isEqualTo(kc.issuer().toString());
        assertThat(m.expiresAt()).isEqualTo(ok.expiresAt());
        assertThat(m.since()).isBetween(before.minusSeconds(1), Instant.now());
        assertThat(m.toString()).doesNotContain(m.token()).contains(m.jti());
        assertThat(m.event().jti()).isEqualTo(m.jti());
    }

    @Test
    void aSessionWithoutATicketCarriesTheNoTicketMarker() {
        directorSignedIn();
        Minted m = identity.mint(spec(null), launch());
        assertThat(((Ok) verifier.verify(m.token())).claims().ticket()).isEqualTo(SessionIdentity.NO_TICKET);
        assertThat(identity.claimsOf(spec(null), "L").ticket().value()).isEqualTo("T-0");
        assertThat(identity.claimsOf(spec(T), "L").ticket()).isEqualTo(T);
    }

    @Test
    void beforeThePlanIsApprovedWithoutTheDirectorsSignInNoSessionStarts() {
        statuses.put(M, MissionStatus.AWAITING_APPROVAL);
        assertThatThrownBy(() -> identity.mint(spec(T), launch()))
                .isInstanceOf(IdentityUnavailableException.class)
                .hasMessageContaining("waiting for the director's sign-in");
    }

    @Test
    void workNeedsTheMissionsDelegationAndNeverTheSignIn() {
        directorSignedIn();
        for (MissionStatus s : List.of(MissionStatus.IN_PROGRESS, MissionStatus.SUSPENDED)) {
            statuses.put(M, s);
            assertThatThrownBy(() -> identity.mint(spec(T), launch()))
                    .isInstanceOf(IdentityUnavailableException.class)
                    .hasMessageContaining("no delegation");
        }
        delegate();
        for (MissionStatus s : List.of(MissionStatus.IN_PROGRESS, MissionStatus.SUSPENDED)) {
            statuses.put(M, s);
            assertThat(identity.mint(spec(T), launch()).token()).isNotBlank();
        }
        delegations.revokeLocally(M);
        assertThatThrownBy(() -> identity.mint(spec(T), launch())).hasMessageContaining("revoked");
    }

    @Test
    void aCompletedMissionServesItsRetrospectiveOnlyOnTheDelegation() {
        delegate();
        statuses.put(M, MissionStatus.COMPLETED);
        assertThatThrownBy(() -> identity.mint(spec(T), launch()))
                .isInstanceOf(IdentityUnavailableException.class)
                .hasMessageContaining("only its retrospective");
        assertThat(identity.mint(spec(RETRO), launch()).token()).isNotBlank();
    }

    @Test
    void theRetrospectiveOfAMissionApprovedWithIamOffRidesTheSignIn() {
        directorSignedIn();
        statuses.put(M, MissionStatus.COMPLETED);
        assertThat(identity.mint(spec(RETRO), launch()).token()).isNotBlank();
        assertThat(identity.mint(spec(T), launch()).token())
                .as("no delegation, nothing to protect")
                .isNotBlank();
    }

    @Test
    void aBlockedIdentityAndARevokedAgentStartNothing() {
        directorSignedIn();
        clients.revokeLocally(ADA);
        assertThatThrownBy(() -> identity.mint(spec(T), launch())).hasMessageContaining("identity is revoked");
        clients.enable(ADA);
        status.checkFailed();
        assertThatThrownBy(() -> identity.mint(spec(T), launch())).hasMessageContaining("Keycloak did not answer");
    }

    @Test
    void anAgentWithoutAKeycloakSecretOrAKeycloakThatRefusesStartsNothing() {
        directorSignedIn();
        vault.delete(KeycloakClient.vaultName(ADA));
        assertThatThrownBy(() -> identity.mint(spec(T), launch())).hasMessageContaining("no Keycloak secret");
        clients.ensure(ADA);
        assertThat(identity.mint(spec(T), launch()).token()).isNotBlank();
        kc.disableClient("floor42-agent-ada");
        assertThatThrownBy(() -> identity.mint(spec(T), launch()))
                .isInstanceOf(IdentityUnavailableException.class)
                .hasMessageContaining("Keycloak gave no token");
    }

    @Test
    void aSimulatedSessionGetsATokenNoHookWouldAccept() {
        Minted m = identity.mintSimulated(spec(T)).orElseThrow();
        assertThat(m.issuer()).isEqualTo(SimulatedIssuer.ISSUER);
        assertThat(m.jti()).isNotBlank();
        assertThat(m.expiresAt())
                .isBetween(
                        Instant.now().plus(Duration.ofMinutes(59)),
                        Instant.now().plus(Duration.ofMinutes(61)));
        assertThat(verifier.verify(m.token())).isInstanceOf(Refused.class);
        assertThat(((Refused) verifier.verify(m.token())).reason()).isEqualTo(TokenRefusal.ISSUER_UNKNOWN);
    }
}
