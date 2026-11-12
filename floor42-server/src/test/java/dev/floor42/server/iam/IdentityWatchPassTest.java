package dev.floor42.server.iam;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.KeycloakClient.Tokens;
import dev.floor42.server.iam.SessionIdentity.Minted;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One pass of the 60 s check: only Keycloak's own "inactive" ends a session, a refusal never does, a pass that runs out
 * of time asks the missed sessions first next time, and every outcome reaches the status.
 */
class IdentityWatchPassTest {

    @TempDir
    Path dir;

    FakeKeycloak kc;
    SecretVault vault;
    IamStatus status;
    IamSessions sessions;
    Delegations delegations;
    AgentClients clients;
    IdentityWatch watch;
    AgentTokenVerifier verifier;
    boolean iamOn = true;
    final Set<AgentId> revokedAgents = new HashSet<>();
    final List<SessionClaims> stopped = new ArrayList<>();
    final List<Object> recovered = new ArrayList<>();
    final List<AgentId> staff = new ArrayList<>(List.of(new AgentId("ada")));

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        kc.registerClient("floor42-daemon", "daemon-secret-0123");
        kc.registerClient("floor42-api", "api-secret-4567");
        kc.registerClient("floor42-desk", null);
        vault = new SecretVault(dir);
        vault.put("floor42-daemon", "daemon-secret-0123".getBytes(UTF_8));
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        var json = new ObjectMapper();
        var settings =
                new IamSettings(dir, Optional.of(true), Optional.of(kc.issuer().toString()), Optional.empty(), json) {
                    @Override
                    public boolean enabled() {
                        return iamOn;
                    }
                };
        var keycloak = new KeycloakClient(kc.issuer().toString(), vault, json);
        clients = new AgentClients(keycloak, settings, () -> staff);
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
        verifier = new AgentTokenVerifier(kc.issuer(), "floor42-api", jwks, Clock.systemUTC());
        delegations = new Delegations(
                dir.resolve("iam").resolve("delegations.json"),
                vault,
                keycloak,
                () -> true,
                Optional::empty,
                null,
                json);
        sessions = new IamSessions(verifier, owners, () -> revokedAgents, (m, since) -> false, () -> 0, stopped::add);
        Event<IamRecovered> event = (Event<IamRecovered>)
                Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Event.class}, (p, m, a) -> {
                    if (m.getName().equals("fire")) recovered.add(a[0]);
                    return null;
                });
        watch = new IdentityWatch(settings, status, jwks, keycloak, sessions, delegations, clients, event);
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private String live(String agent, String mission) {
        String t = kc.exchangeForTest(
                "floor42-agent-" + agent,
                kc.directorToken("director"),
                Map.of(
                        "audience",
                        "floor42-api",
                        "floor42_launch",
                        UUID.randomUUID().toString(),
                        "floor42_mission",
                        mission,
                        "floor42_ticket",
                        "T-1"));
        Ok ok = (Ok) verifier.verify(t);
        sessions.register(null, ok.claims(), new Minted(t, ok.jti(), ok.issuer(), ok.expiresAt(), Instant.now()));
        return t;
    }

    private long introspections() {
        return kc.requests().stream()
                .filter(r -> r.get("_path").endsWith("/introspect"))
                .count();
    }

    @Test
    void withIamOffNothingIsAskedOfKeycloak() {
        iamOn = false;
        int before = kc.requests().size();
        watch.checkNow();
        assertThat(kc.requests()).hasSize(before);
    }

    @Test
    void aGoodPassWithLiveSessionsEndsNoneAndKeepsTheStatusUp() {
        live("ada", "M-1");
        live("ada", "M-2");
        long before = introspections();
        watch.checkNow();
        assertThat(introspections() - before).isEqualTo(3); // the probe and each live token
        assertThat(stopped).isEmpty();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(status.view().failedChecks()).isZero();
        assertThat(recovered).isEmpty();
    }

    @Test
    void aTokenKeycloakCallsInactiveEndsThatSessionOnly() {
        String a = live("ada", "M-1");
        live("ada", "M-2");
        kc.refuseIntrospectionOf("never");
        Ok ok = (Ok) verifier.verify(a);
        kc.revokeAll();
        String b = live("ada", "M-3");
        watch.checkNow();
        assertThat(stopped).hasSize(2);
        assertThat(stopped.stream().map(SessionClaims::mission).map(MissionId::toString))
                .containsExactlyInAnyOrder("M-1", "M-2");
        assertThat(sessions.authenticate(a, null, null))
                .isInstanceOf(dev.floor42.server.runtime.HookRegistry.Refused.class);
        assertThat(ok.claims().mission().toString()).isEqualTo("M-1");
        assertThat(b).isNotBlank();
    }

    @Test
    void aRefusalAboutOneTokenEndsNoSessionAndCountsAsAFailedPass() {
        String a = live("ada", "M-1");
        String b = live("ada", "M-2");
        kc.refuseIntrospectionOf(a);
        watch.checkNow();
        assertThat(stopped).isEmpty();
        assertThat(status.view().failedChecks()).isEqualTo(1);
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UNREACHABLE);
        assertThat(b).isNotBlank();
    }

    @Test
    void aKeycloakThatDoesNotAnswerIsAFailedPassNeverARevocation() {
        live("ada", "M-1");
        kc.down(true);
        watch.checkNow();
        assertThat(stopped).isEmpty();
        assertThat(status.view().failedChecks()).isEqualTo(1);
        kc.down(false);
        watch.checkNow();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(status.view().failedChecks()).isZero();
    }

    @Test
    void aKeycloakThatCameBackFiresTheRecoveryOnceAndNotWhenItNeverWentAway() {
        kc.down(true);
        watch.checkNow();
        kc.down(false);
        watch.checkNow();
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0)).isInstanceOf(IamRecovered.class);
        watch.checkNow();
        assertThat(recovered).hasSize(1);
    }

    @Test
    void aRefusedApiCredentialIsAStatusFailureAndNoSessionIsEnded() {
        live("ada", "M-1");
        vault.put("floor42-api", "wrong-secret-0000".getBytes(UTF_8));
        watch.checkNow();
        assertThat(stopped).isEmpty();
        assertThat(status.view().code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        watch.checkNow();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(recovered).hasSize(1);
    }

    @Test
    void aMissingApiSecretIsAStatusFailure() {
        live("ada", "M-1");
        vault.delete("floor42-api");
        watch.checkNow();
        assertThat(stopped).isEmpty();
        assertThat(status.view().code()).isEqualTo(IamStatus.API_SECRET_MISSING);
    }

    @Test
    void revokedAgentsAreStoppedAtTheStartOfEveryPass() {
        live("ada", "M-1");
        revokedAgents.add(new AgentId("ada"));
        watch.checkNow();
        assertThat(stopped).hasSize(1);
    }

    @Test
    void aPassOutOfTimeAsksTheMissedSessionsFirstNextTimeAndEndsNone() {
        String a = live("ada", "M-1");
        String b = live("ada", "M-2");
        AtomicLong t = new AtomicLong();
        watch.clock(() -> t.getAndAdd(IdentityWatch.DEADLINE.toNanos() + 1));
        long before = introspections();
        watch.checkNow();
        assertThat(introspections() - before)
                .as("only the probe, every session was late")
                .isEqualTo(1);
        assertThat(watch.behind()).hasSize(2);
        assertThat(stopped).isEmpty();
        assertThat(status.view().reason()).contains("ran out of time");
        watch.clock(System::nanoTime);
        watch.checkNow();
        assertThat(watch.behind()).isEmpty();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(a + b).isNotBlank();
    }

    @Test
    void aPassKeepsTheDeadlineBudgetForTheSessionsItReaches() {
        live("ada", "M-1");
        AtomicLong t = new AtomicLong();
        watch.clock(() -> t.addAndGet(1_000_000L));
        watch.checkNow();
        assertThat(watch.behind()).isEmpty();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
    }

    @Test
    void aGoodPassRetriesTheRevocationsAndDisablesKeycloakMissed() {
        delegations.grant(
                new MissionId("M-5"),
                new Tokens("a", kc.refreshTokenFor("director", "floor42-desk"), null, Instant.now()),
                List.of());
        delegations.revokeLocally(new MissionId("M-5"));
        clients.ensure(new AgentId("ada"));
        kc.down(true);
        clients.dismissed(new AgentId("ada"));
        kc.down(false);
        assertThat(vault.get(Delegations.entryOf(new MissionId("M-5")))).isPresent();
        watch.checkNow();
        assertThat(vault.get(Delegations.entryOf(new MissionId("M-5")))).isEmpty();
        assertThat(clients.views().get(0).enabled())
                .as("the missed disable was retried")
                .isFalse();
    }

    @Test
    void theClockAtTheDeadlineExactlyIsAlreadyLate() {
        live("ada", "M-1");
        AtomicLong calls = new AtomicLong();
        watch.clock(() -> calls.getAndIncrement() == 0 ? 0L : IdentityWatch.DEADLINE.toNanos());
        long before = introspections();
        watch.checkNow();
        assertThat(introspections() - before).as("only the probe").isEqualTo(1);
        assertThat(watch.behind()).hasSize(1);
    }

    @Test
    void theSessionsThePreviousPassMissedAreAskedBeforeTheOthers() {
        live("ada", "M-1");
        live("ada", "M-2");
        for (int pass = 0; pass < 2; pass++) {
            AtomicLong calls = new AtomicLong();
            // the deadline is computed, one session is asked, the rest are late
            watch.clock(() -> calls.getAndIncrement() < 2 ? 0L : IdentityWatch.DEADLINE.toNanos() + 1);
            Set<String> before = watch.behind();
            watch.checkNow();
            assertThat(watch.behind()).hasSize(1);
            if (pass == 1)
                assertThat(watch.behind())
                        .as("the one asked first time is the one missed now")
                        .isNotEqualTo(before);
        }
    }

    @Test
    void theTimerRunsOnlyWithIamOnOnItsOwnDaemonThread() {
        iamOn = false;
        watch.start(null);
        assertThat(identityWatchThreads()).isZero();
        iamOn = true;
        watch.stop(null);
        watch.start(null);
        try {
            assertThat(identityWatchThreads()).isEqualTo(1);
            assertThat(Thread.getAllStackTraces().keySet().stream()
                            .filter(t -> t.getName().equals("identity-watch"))
                            .allMatch(Thread::isDaemon))
                    .isTrue();
        } finally {
            watch.stop(null);
        }
    }

    private static long identityWatchThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("identity-watch") && t.isAlive())
                .count();
    }
}
