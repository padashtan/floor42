package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.HookRegistry.Accepted;
import dev.floor42.server.runtime.HookRegistry.Authentication;
import dev.floor42.server.runtime.HookRegistry.Refused;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a hook or MCP call must pass: verified token, then Floor42's own record of the session, then revocation, then
 * the process that owns the socket. Real verifier and signing keys (fake Keycloak), nothing else is real.
 */
class IamSessionsTest {

    private static final InetSocketAddress REMOTE = InetSocketAddress.createUnresolved("127.0.0.1", 50001);
    private static final InetSocketAddress LOCAL = InetSocketAddress.createUnresolved("127.0.0.1", 7420);
    private static final long PID = 4242;

    private FakeKeycloak kc;
    private AgentTokenVerifier verifier;
    private final Set<AgentId> revokedAgents = new HashSet<>();
    private final AtomicBoolean delegationRevoked = new AtomicBoolean();
    private final AtomicInteger failedChecks = new AtomicInteger();
    private final AtomicBoolean credentialRefused = new AtomicBoolean();
    private final AtomicReference<OptionalLong> owner = new AtomicReference<>(OptionalLong.of(PID));
    private final List<SessionClaims> stopped = new ArrayList<>();
    private final Governor governor = (Governor)
            Proxy.newProxyInstance(Governor.class.getClassLoader(), new Class<?>[] {Governor.class}, (p, m, a) -> null);
    private IamSessions sessions;

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        Clock clock = Clock.systemUTC();
        Jwks jwks =
                new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        verifier = new AgentTokenVerifier(kc.issuer(), "floor42-api", jwks, clock);
        sessions = new IamSessions(
                verifier,
                (client, server) -> owner.get(),
                () -> revokedAgents,
                (m, since) -> delegationRevoked.get(),
                failedChecks::get,
                credentialRefused::get,
                stopped::add);
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private String token(String agent, String launch, String mission, String ticket) {
        return kc.exchangeForTest(
                "floor42-agent-" + agent,
                kc.directorToken("director"),
                Map.of(
                        "audience",
                        "floor42-api",
                        "floor42_launch",
                        launch,
                        "floor42_mission",
                        mission,
                        "floor42_ticket",
                        ticket));
    }

    private String token() {
        return token("ada", UUID.randomUUID().toString(), "M-1", "T-2");
    }

    /** Registers the session Floor42 launched for this token, claims taken from the token unless overridden. */
    private SessionClaims register(String token, String jti, SessionClaims known) {
        Ok ok = (Ok) verifier.verify(token);
        SessionClaims claims = known == null ? ok.claims() : known;
        sessions.register(
                governor,
                claims,
                new Minted(token, jti == null ? ok.jti() : jti, ok.issuer(), ok.expiresAt(), Instant.now()));
        return claims;
    }

    private String registered() {
        String t = token();
        register(t, null, null);
        sessions.bindProcess(t, PID);
        return t;
    }

    private TokenRefusal refusal(Authentication a) {
        assertThat(a).isInstanceOf(Refused.class);
        return ((Refused) a).reason();
    }

    @Test
    void aLiveBoundSessionFromItsOwnProcessIsAccepted() {
        String t = registered();
        Authentication a = sessions.authenticate(t, REMOTE, LOCAL);
        assertThat(a).isInstanceOf(Accepted.class);
        assertThat(((Accepted) a).governor()).isSameAs(governor);
        assertThat(((Accepted) a).claims().agent()).isEqualTo(new AgentId("ada"));
        assertThat(stopped).isEmpty();
    }

    @Test
    void noTokenAndGarbageAreMalformed() {
        assertThat(refusal(sessions.authenticate(null, REMOTE, LOCAL))).isEqualTo(TokenRefusal.MALFORMED);
        Refused r = (Refused) sessions.authenticate("a.b.c", REMOTE, LOCAL);
        assertThat(r.reason()).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(r.governor()).isNull();
    }

    @Test
    void aTokenOfAnotherIssuerReachesNoGovernor() {
        String t = registered();
        String foreign = kc.forge(t, b -> b.put("iss", "http://evil.example/realms/x"));
        Refused r = (Refused) sessions.authenticate(foreign, REMOTE, LOCAL);
        assertThat(r.reason()).isEqualTo(TokenRefusal.ISSUER_UNKNOWN);
        assertThat(r.governor()).isNull();
    }

    @Test
    void aValidTokenOfAnUnknownLaunchIsAnEndedSession() {
        Refused r = (Refused) sessions.authenticate(token(), REMOTE, LOCAL);
        assertThat(r.reason()).isEqualTo(TokenRefusal.SESSION_ENDED);
        assertThat(r.governor()).isNull();
        assertThat(r.jti()).isNotBlank();
        assertThat(r.launch()).isNotBlank();
    }

    @Test
    void aTokenWhoseJtiIsNotTheRecordedOneIsAnEndedSession() {
        String t = token();
        register(t, "another-jti", null);
        sessions.bindProcess(t, PID);
        Refused r = (Refused) sessions.authenticate(t, REMOTE, LOCAL);
        assertThat(r.reason()).isEqualTo(TokenRefusal.SESSION_ENDED);
        assertThat(sessions.governorOf(t)).isEmpty();
    }

    @Test
    void theClaimsMustAgreeWithFloor42sOwnRecord() {
        String launch = UUID.randomUUID().toString();
        String t = token("ada", launch, "M-1", "T-2");
        SessionClaims known = ((Ok) verifier.verify(t)).claims();
        for (var c : List.of(
                Map.entry(
                        new SessionClaims(launch, new AgentId("bob"), known.mission(), known.ticket()),
                        TokenRefusal.WRONG_AGENT),
                Map.entry(
                        new SessionClaims(launch, known.agent(), new MissionId("M-9"), known.ticket()),
                        TokenRefusal.WRONG_MISSION),
                Map.entry(
                        new SessionClaims(launch, known.agent(), known.mission(), new TicketId("T-9")),
                        TokenRefusal.WRONG_TICKET))) {
            register(t, null, c.getKey());
            sessions.bindProcess(t, PID);
            Refused r = (Refused) sessions.authenticate(t, REMOTE, LOCAL);
            assertThat(r.reason()).isEqualTo(c.getValue());
            assertThat(r.governor()).isSameAs(governor);
            sessions.unregister(t);
        }
    }

    @Test
    void aTokenWithAnotherAudienceBesidesOursIsRefused() {
        String t = registered();
        String twoAudiences =
                kc.forge(t, b -> b.putArray("aud").add("floor42-api").add("res-x"));
        assertThat(refusal(sessions.authenticate(twoAudiences, REMOTE, LOCAL))).isEqualTo(TokenRefusal.WRONG_AUDIENCE);
        String arrayOfOne = kc.forge(t, b -> b.putArray("aud").add("floor42-api"));
        assertThat(sessions.authenticate(arrayOfOne, REMOTE, LOCAL)).isInstanceOf(Accepted.class);
    }

    @Test
    void aRevokedAgentIsRefusedAndItsSessionStopped() {
        String t = registered();
        revokedAgents.add(new AgentId("ada"));
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.REVOKED);
        assertThat(stopped).hasSize(1);
        assertThat(stopped.get(0).agent()).isEqualTo(new AgentId("ada"));
        revokedAgents.clear();
        assertThat(sessions.authenticate(t, REMOTE, LOCAL)).isInstanceOf(Accepted.class);
    }

    @Test
    void aRevokedDelegationRefusesTheMissionsSession() {
        String t = registered();
        delegationRevoked.set(true);
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.REVOKED);
        assertThat(stopped).hasSize(1);
    }

    @Test
    void whenKeycloakReportsTheTokenInactiveItIsRefusedForGoodAndStoppedOnce() {
        String t = registered();
        SessionClaims claims = ((Ok) verifier.verify(t)).claims();
        sessions.markInactive(claims);
        sessions.markInactive(claims);
        assertThat(stopped).containsExactly(claims);
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.REVOKED);
    }

    @Test
    void unregisteringEndsTheSessionAndForgetsTheInactiveMark() {
        String t = registered();
        SessionClaims claims = ((Ok) verifier.verify(t)).claims();
        sessions.markInactive(claims);
        sessions.unregister(t);
        assertThat(sessions.liveTokens()).isEmpty();
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.SESSION_ENDED);
        register(t, null, null);
        sessions.bindProcess(t, PID);
        assertThat(sessions.authenticate(t, REMOTE, LOCAL)).isInstanceOf(Accepted.class);
        sessions.unregister("some other token");
        assertThat(sessions.liveTokens()).hasSize(1);
    }

    @Test
    void twoFailedBackgroundChecksRefuseRunningSessions() {
        String t = registered();
        failedChecks.set(IamSessions.FAILED_CHECKS_LIMIT - 1);
        assertThat(sessions.authenticate(t, REMOTE, LOCAL)).isInstanceOf(Accepted.class);
        failedChecks.set(IamSessions.FAILED_CHECKS_LIMIT);
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.KEYCLOAK_UNREACHABLE);
        credentialRefused.set(true);
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.KEYCLOAK_REFUSED);
        assertThat(stopped).isEmpty();
    }

    @Test
    void theSocketMustBeOwnedByTheLaunchedProcess() {
        String t = registered();
        owner.set(OptionalLong.of(PID + 1));
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        owner.set(OptionalLong.empty());
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        owner.set(OptionalLong.of(PID));
        assertThat(refusal(sessions.authenticate(t, null, LOCAL))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        assertThat(refusal(sessions.authenticate(t, REMOTE, null))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        assertThat(sessions.authenticate(t, REMOTE, LOCAL)).isInstanceOf(Accepted.class);
    }

    @Test
    void aSessionWhoseProcessWasNeverBoundIsRefusedAfterTheBindWait() {
        String t = token();
        register(t, null, null);
        long began = System.nanoTime();
        assertThat(refusal(sessions.authenticate(t, REMOTE, LOCAL))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        assertThat(System.nanoTime() - began).isGreaterThanOrEqualTo(IamSessions.BIND_WAIT.toNanos() - 200_000_000L);
    }

    @Test
    void aPidIsBoundOnlyToTheSessionHoldingThatToken() {
        String a = token("ada", UUID.randomUUID().toString(), "M-1", "T-1");
        String b = token("bob", UUID.randomUUID().toString(), "M-2", "T-2");
        SessionClaims ca = register(a, null, null);
        SessionClaims cb = register(b, null, null);
        assertThat(sessions.boundPid(ca.launch())).isEmpty();
        assertThat(sessions.boundPid("unknown")).isEmpty();
        sessions.bindProcess(a, PID);
        assertThat(sessions.boundPid(ca.launch())).hasValue(PID);
        assertThat(sessions.boundPid(cb.launch())).isEmpty();
    }

    @Test
    void governorOfNeedsAVerifiedLiveToken() {
        String t = registered();
        assertThat(sessions.governorOf(t)).containsSame(governor);
        assertThat(sessions.governorOf(null)).isEmpty();
        assertThat(sessions.governorOf("a.b.c")).isEmpty();
        assertThat(sessions.governorOf(token())).isEmpty();
    }

    @Test
    void stopRevokedStopsOnlyTheRevokedSessionsAndCountsThem() {
        String ada = token("ada", UUID.randomUUID().toString(), "M-1", "T-1");
        String bob = token("bob", UUID.randomUUID().toString(), "M-2", "T-2");
        register(ada, null, null);
        SessionClaims bobClaims = register(bob, null, null);
        assertThat(sessions.stopRevoked()).isZero();
        revokedAgents.add(new AgentId("ada"));
        assertThat(sessions.stopRevoked()).isEqualTo(1);
        assertThat(stopped).hasSize(1);
        assertThat(stopped.get(0).agent()).isEqualTo(new AgentId("ada"));
        assertThat(stopped).doesNotContain(bobClaims);
    }

    @Test
    void whatTheScreenMayShowIsTheDecodedTokenAndEightCharactersOfTheSignature() {
        String t = registered();
        var shown = sessions.shown();
        assertThat(shown).hasSize(1);
        var s = shown.get(0);
        assertThat(s.launch()).isEqualTo(s.claims().launch());
        assertThat(s.header()).containsEntry("alg", "RS256");
        assertThat(s.payload()).containsEntry("azp", "floor42-agent-ada");
        assertThat(s.jti()).isEqualTo(((Ok) verifier.verify(t)).jti());
        assertThat(s.signaturePrefix())
                .hasSize(11)
                .isEqualTo(t.substring(t.lastIndexOf('.') + 1, t.lastIndexOf('.') + 9) + "...");
    }

    @Test
    void registerHandsTheTokenBack() {
        String t = token();
        Ok ok = (Ok) verifier.verify(t);
        assertThat(sessions.register(
                        governor, ok.claims(), new Minted(t, ok.jti(), ok.issuer(), ok.expiresAt(), Instant.now())))
                .isEqualTo(t);
        assertThat(sessions.liveTokens()).containsEntry(ok.claims(), t);
    }
}
