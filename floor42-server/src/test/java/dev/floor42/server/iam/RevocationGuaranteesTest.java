package dev.floor42.server.iam;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.server.iam.ResourceBroker.BrokerResult;
import dev.floor42.server.iam.ResourceBroker.Refusal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each revocation path, measured against the fake Keycloak: after how many hook calls or background passes (one pass is
 * {@link IdentityWatch#PERIOD}, 60 s, plus the time the pass itself takes) the next call is refused. The numbers behind
 * docs/iam-revocation.md. Nothing here waits on a wall clock: a pass is {@code watch.checkNow()}, the token clock is
 * a test clock.
 */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
class RevocationGuaranteesTest extends BrokerTestBase {

    private static final long ME = ProcessHandle.current().pid();
    private static final String AGENT_CLIENT = "floor42-agent-chris";

    @Inject
    IdentityWatch watch;

    @Inject
    IamSessions sessions;

    @Inject
    AgentClients clients;

    @Inject
    ResourceBroker broker;

    static final java.util.concurrent.atomic.AtomicInteger recoveries = new java.util.concurrent.atomic.AtomicInteger();

    void onRecovered(@jakarta.enterprise.event.Observes IamRecovered ignored) {
        recoveries.incrementAndGet();
    }

    @BeforeEach
    void keycloakWell() {
        var kc = IamOnProfile.fake;
        kc.down(false);
        kc.acceptAllIntrospections();
        if (!kc.clients().contains("floor42-api")) kc.registerClient("floor42-api", "api-secret-4567");
        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        kc.registerClient(AGENT_CLIENT, "s"); // enabled again, whatever an earlier test did
        clients.enable(CHRIS); // the local revocation of the agent, if an earlier test set it
        status.start();
    }

    @AfterEach
    void keycloakBack() {
        IamOnProfile.fake.down(false);
        IamOnProfile.fake.acceptAllIntrospections();
    }

    /** The hook's answer for this token: "OK" or the refusal reason. */
    private String hook(String token) {
        var r = given().contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .body(preToolUse("Read", UUID.randomUUID().toString()))
                .post("/hooks/pre-tool-use")
                .then();
        return r.extract().statusCode() == 200 ? "OK" : r.extract().path("reason");
    }

    private void brokerRefused(Setup s) {
        brokerRefused(s, "IDENTITY_UNAVAILABLE");
    }

    private void brokerRefused(Setup s, String code) {
        assertThatThrownBy(() -> broker.call(claims(s), RESOURCE, "GET", "/api/x", null))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.code()).isEqualTo(code));
    }

    private static long exchanges() {
        return IamOnProfile.fake.requests().stream()
                .filter(f -> AUDIENCE.equals(f.get("audience")))
                .count();
    }

    private static long revokeRequests() {
        return IamOnProfile.fake.requests().stream()
                .filter(r -> r.get("_path").endsWith("/revoke"))
                .count();
    }

    // ---- 1. the director revokes the mission's delegation (UI), or the mission ends

    @Test
    void revokedDelegationRefusesTheNextHookAndTheNextResourceCallWithNoPassAndNoKeycloak() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        String second = liveSession(s, UUID.randomUUID().toString(), ME);
        assertThat(hook(token)).isEqualTo("OK");
        assertThat(broker.call(claims(s), RESOURCE, "GET", "/api/x", null).status())
                .isEqualTo(200);
        long before = exchanges();
        int hitsBefore = hits.size();

        IamOnProfile.fake.down(true); // Keycloak does not even need to answer
        delegations.revokeLocally(s.mission());

        assertThat(sessions.stopRevoked()).isGreaterThanOrEqualTo(2); // the sweep reaches both, no call needed
        assertThat(hook(token)).isEqualTo("REVOKED"); // zero passes, zero calls
        assertThat(hook(second)).isEqualTo("REVOKED");
        brokerRefused(s, "RESOURCE_NOT_GRANTED"); // the catalogue refuses before any token is asked for
        assertThat(exchanges()).isEqualTo(before); // no token was even asked for
        assertThat(hits).hasSize(hitsBefore);
    }

    @Test
    void aMissionThatEndedOrCompletedForARetrospectiveCutsRunningSessionsAtOnce() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String running = liveSession(s, ME);
        assertThat(hook(running)).isEqualTo("OK");
        delegations.closeToWork(s.mission()); // MissionCompleted with a retrospective due
        assertThat(hook(running)).isEqualTo("REVOKED");
        brokerRefused(s, "IDENTITY_REFUSED"); // Floor42's own decision, not a provider failure
    }

    @Test
    void keycloaksSideOfTheRevocationWaitsForKeycloakAndIsTakenByTheFirstGoodPass() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        broker.call(claims(s), RESOURCE, "GET", "/api/x", null);

        IamOnProfile.fake.down(true);
        delegations.revokeLocally(s.mission());
        delegations.revokeRemote(s.mission()); // cannot reach Keycloak: the vault entry stays, encrypted
        long failedTry = revokeRequests(); // the fake records a request it answered 503 to
        assertThat(vault.get(Delegations.entryOf(s.mission()))).isPresent();
        IamOnProfile.fake.down(false);
        assertThat(revokeRequests()).isEqualTo(failedTry); // the offline session lives at Keycloak until told

        watch.checkNow(); // one good pass retries it
        assertThat(vault.get(Delegations.entryOf(s.mission()))).isEmpty();
        assertThat(revokeRequests()).isEqualTo(failedTry + 1); // the retry
    }

    // ---- 2. the agent's Keycloak client is disabled or its secret rotated in Keycloak

    @Test
    void aClientDisabledInKeycloakIsRefusedByTheResourceBrokerAtOnceAndByHooksAfterOnePass() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        IamOnProfile.fake.disableClient(AGENT_CLIENT);

        brokerRefused(s); // each call exchanges: Keycloak refuses the disabled client
        assertThat(hook(token)).isEqualTo("OK"); // the window: Floor42 has not asked yet
        watch.checkNow();
        assertThat(hook(token)).isEqualTo("REVOKED"); // one pass
    }

    @Test
    void theDirectorsRevokeOfAnAgentRefusesItsHooksAtOnce() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        clients.revoke(CHRIS);
        assertThat(hook(token)).isEqualTo("REVOKED"); // zero passes: the local flag, then Keycloak is told
        brokerRefused(s);
    }

    @Test
    void aRotatedSecretRevokesNothingAlreadyIssuedAndOnlyBreaksNewExchangesDoneWithTheOldOne() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        IamOnProfile.fake.registerClient(AGENT_CLIENT, "rotated-in-the-admin-console");

        brokerRefused(s); // the vault still holds the old secret
        for (int pass = 1; pass <= 3; pass++) {
            watch.checkNow();
            assertThat(hook(token)).isEqualTo("OK"); // introspection stays active:true; only expiry ends it
        }
        // rotated through Floor42: the vault gets the new secret, the issued token is still not touched
        clients.ensure(CHRIS);
        clients.rotate(CHRIS);
        assertThat(broker.call(claims(s), RESOURCE, "GET", "/api/x", null).status())
                .isEqualTo(200);
        watch.checkNow();
        assertThat(hook(token)).isEqualTo("OK");
    }

    // ---- 3. a token already issued to a session

    @Test
    void aTokenIsAcceptedLocallyUntilItsExpiryPlusThirtySecondsAndNoLonger() throws Exception {
        var kc = IamOnProfile.fake;
        Instant[] now = {Instant.now()};
        Clock clock = new Clock() {
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public Instant instant() {
                return now[0];
            }
        };
        var jwks =
                new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        var local = new AgentTokenVerifier(kc.issuer(), "floor42-api", jwks, clock);
        String jwt = kc.exchangeForTest(
                AGENT_CLIENT,
                kc.directorToken("director"),
                Map.of(
                        "audience",
                        "floor42-api",
                        "floor42_launch",
                        UUID.randomUUID().toString(),
                        "floor42_mission",
                        "M-1",
                        "floor42_ticket",
                        "T-1"));
        Instant exp = Instant.ofEpochSecond(Jwts.parse(jwt).claims().path("exp").asLong());

        now[0] = exp.plus(AgentTokenVerifier.SKEW);
        assertThat(local.verify(jwt)).isInstanceOf(AgentTokenVerifier.Ok.class);
        now[0] = exp.plus(AgentTokenVerifier.SKEW).plusSeconds(1);
        assertThat(local.verify(jwt))
                .isInstanceOfSatisfying(
                        AgentTokenVerifier.Refused.class,
                        r -> assertThat(r.reason()).isEqualTo(TokenRefusal.EXPIRED));
        assertThat(AgentTokenVerifier.SKEW).isEqualTo(Duration.ofSeconds(30));
        assertThat(IamSettings.clamp(1000)).isEqualTo(8); // no session token is configured to live past 8 h
    }

    @Test
    void anInactiveAnswerEndsTheSessionAfterOnePassAndNothingBringsItBack() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        IamOnProfile.fake.revokeAll(); // the token is no longer active at Keycloak
        assertThat(hook(token)).isEqualTo("OK"); // until Floor42 asks
        watch.checkNow();
        assertThat(hook(token)).isEqualTo("REVOKED");
        watch.checkNow();
        assertThat(hook(token)).isEqualTo("REVOKED"); // refused for good for this launch
    }

    @Test
    void aTokenSpecificRefusalNeverEndsASessionHoweverManyPassesRun() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        IamOnProfile.fake.refuseIntrospectionOf(token);
        for (int pass = 1; pass <= 3; pass++) watch.checkNow();
        // The passes count as failed: the unreachable rule, not a verdict on the token
        assertThat(hook(token)).isEqualTo("KEYCLOAK_UNREACHABLE");
    }

    // ---- 4. Keycloak unreachable

    @Test
    void anUnreachableKeycloakBlocksNewSessionsAtTheFirstPassAndRefusesRunningOnesAtTheSecond() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        IamOnProfile.fake.down(true);

        assertThat(hook(token)).isEqualTo("OK"); // keys are cached: nothing has noticed yet
        watch.checkNow();
        assertThat(status.blocksSessions()).isPresent(); // new sessions: pass 1
        assertThat(delegations.blocks(s.mission(), true)).isPresent();
        assertThat(hook(token)).isEqualTo("OK"); // running ones still allowed
        brokerRefused(s); // the broker needs Keycloak for every call: no pass needed
        watch.checkNow();
        assertThat(hook(token)).isEqualTo("KEYCLOAK_UNREACHABLE"); // running ones: pass 2
        assertThat(IdentityWatch.FAILURES_TO_REFUSE).isEqualTo(2);

        delegations.revokeLocally(s.mission()); // local revocation does not need Keycloak
        assertThat(hook(token)).isEqualTo("REVOKED");
    }

    @Test
    void theFirstGoodPassAfterAnOutageResumesEverythingThatWasRefusedForIt() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        IamOnProfile.fake.down(true);
        watch.checkNow();
        watch.checkNow();
        assertThat(hook(token)).isEqualTo("KEYCLOAK_UNREACHABLE");
        assertThat(status.blocksSessions()).isPresent();
        IamOnProfile.fake.down(false);
        assertThat(hook(token)).isEqualTo("KEYCLOAK_UNREACHABLE"); // up again, but nobody has checked yet
        watch.checkNow();
        assertThat(status.blocksSessions()).isEmpty();
        assertThat(hook(token)).isEqualTo("OK");
        assertThat(broker.call(claims(s), RESOURCE, "GET", "/api/x", null).status())
                .isEqualTo(200);
    }

    /** A refused floor42-api credential does not hide an outage: it counts toward the same 2-failure limit. */
    @Test
    void aRefusedApiCredentialFollowedByAnOutageRefusesRunningSessionsAtTheSecondFailedPass() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        String token = liveSession(s, ME);
        vault.put("floor42-api", "wrong-secret-0000".getBytes(UTF_8));
        watch.checkNow();
        assertThat(status.view().failedChecks()).isEqualTo(1);
        IamOnProfile.fake.down(true);
        watch.checkNow(); // the outage is failed check 2
        assertThat(hook(token)).isEqualTo("KEYCLOAK_UNREACHABLE");
    }

    /** The floor42-api credential is probed every pass, with or without a session: only a passing probe clears it. */
    @Test
    void aRefusedApiCredentialStaysRefusedWithNoSessionAndIsNeverClearedByAnOutage() throws Exception {
        sessions.liveTokens().values().forEach(sessions::unregister); // zero live sessions
        assertThat(sessions.liveTokens()).isEmpty();
        vault.put("floor42-api", "wrong-secret-0000".getBytes(UTF_8));
        recoveries.set(0);
        for (int pass = 1; pass <= 3; pass++) {
            watch.checkNow();
            assertThat(status.view().code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
        }
        IamOnProfile.fake.down(true);
        watch.checkNow();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UNREACHABLE);
        IamOnProfile.fake.down(false);
        watch.checkNow(); // back, the secret is still wrong
        assertThat(status.view().code()).isEqualTo(IamStatus.API_CREDENTIAL_REFUSED);
        assertThat(status.view().health()).isNotEqualTo(IamStatus.Health.UP);
        assertThat(recoveries.get()).isZero();

        vault.put("floor42-api", "api-secret-4567".getBytes(UTF_8));
        watch.checkNow();
        assertThat(status.view().health()).isEqualTo(IamStatus.Health.UP);
        assertThat(recoveries.get()).isEqualTo(1);
    }

    // ---- 5. the broker's exchanged tokens

    @Test
    void everyResourceCallExchangesAFreshTokenOfAtMostFiveMinutes() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        long before = exchanges();
        BrokerResult one = broker.call(claims(s), RESOURCE, "GET", "/api/x", null);
        BrokerResult two = broker.call(claims(s), RESOURCE, "GET", "/api/x", null);
        assertThat(exchanges() - before).isEqualTo(2); // no cache, no reuse
        assertThat(one.jti()).isNotEqualTo(two.jti());
        for (String header : authorizations) {
            var c = Jwts.parse(header.substring("Bearer ".length())).claims();
            assertThat(c.path("exp").asLong() - c.path("iat").asLong()).isLessThanOrEqualTo(300);
        }
        assertThat(ResourceBroker.MAX_TOKEN_SECONDS).isEqualTo(300);
    }

    @Test
    void aTokenOfMoreThanFiveMinutesIsNeverUsedAndOneOfExactlyFiveIs() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        broker.call(claims(s), RESOURCE, "GET", "/api/x", null);
        String real = authorizations.getFirst().substring("Bearer ".length());
        var resource = catalogue.find(RESOURCE).orElseThrow();
        long iat = Jwts.parse(real).claims().path("iat").asLong();
        String exact = IamOnProfile.fake.forge(real, n -> n.put("exp", iat + 300));
        String over = IamOnProfile.fake.forge(real, n -> n.put("exp", iat + 301));
        assertThat(ResourceBroker.checked(exact, claims(s), resource)).isNotBlank();
        assertThatThrownBy(() -> ResourceBroker.checked(over, claims(s), resource))
                .isInstanceOfSatisfying(Refusal.class, r -> assertThat(r.code()).isEqualTo("IDENTITY_UNAVAILABLE"));
    }

    @Test
    void afterTheDelegationIsRevokedAtKeycloakTheNextResourceCallIsRefusedWithNoPass() throws Exception {
        Setup s = brokerSetup(List.of(RESOURCE), List.of(RESOURCE));
        broker.call(claims(s), RESOURCE, "GET", "/api/x", null);
        int hitsBefore = hits.size();
        IamOnProfile.fake.revokeAll(); // revoked at Keycloak, Floor42 not told
        brokerRefused(s); // the broker refreshes the delegation on every call: Keycloak says no
        assertThat(hits).hasSize(hitsBefore);
        assertThat(delegations.isRevoked(s.mission())).isTrue(); // and the refusal is remembered locally
    }
}
