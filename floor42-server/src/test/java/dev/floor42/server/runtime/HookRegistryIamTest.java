package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.iam.AgentTokenVerifier;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.FakeKeycloak;
import dev.floor42.server.iam.IamSessions;
import dev.floor42.server.iam.Jwks;
import dev.floor42.server.iam.SessionClaims;
import dev.floor42.server.iam.SessionIdentity.Minted;
import dev.floor42.server.iam.TokenRefusal;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.HookRegistry.Accepted;
import dev.floor42.server.runtime.HookRegistry.Authentication;
import dev.floor42.server.runtime.HookRegistry.Refused;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The checks behind every hook and MCP call in IAM mode, one refusal at a time (no HTTP, no daemon). */
class HookRegistryIamTest {

    private static final AgentId ADA = new AgentId("ada");
    private static final InetSocketAddress CLIENT = new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
    private static final InetSocketAddress SERVER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 7420);

    private FakeKeycloak kc;
    private AgentTokenVerifier verifier;
    private HookRegistry hooks;
    private final Governor governor = new Governor() {
        @Override
        public Verdict decide(RuntimeEvent.ToolRequested call) {
            return Verdict.ALLOW;
        }

        @Override
        public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
            return null;
        }

        @Override
        public void completed(RuntimeEvent.ToolCompleted result) {}
    };
    private Set<AgentId> revokedAgents = Set.of();
    private boolean delegationRevoked;
    private java.time.Instant revokedAt;
    private int failedChecks;
    private OptionalLong owner = OptionalLong.of(4242);
    private final List<SessionClaims> stopped = new ArrayList<>();
    private final AtomicInteger lookups = new AtomicInteger();

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        var jwks = new Jwks(
                URI.create(kc.issuer() + "/protocol/openid-connect/certs"),
                HttpClient.newHttpClient(),
                Clock.systemUTC());
        verifier = new AgentTokenVerifier(kc.issuer(), "floor42-api", jwks, Clock.systemUTC());
        IamSessions iam = new IamSessions(
                verifier,
                (client, server) -> {
                    lookups.incrementAndGet();
                    return owner;
                },
                () -> revokedAgents,
                (m, since) -> delegationRevoked || (revokedAt != null && !revokedAt.isBefore(since)),
                () -> failedChecks,
                stopped::add);
        hooks = new HookRegistry(() -> true, iam);
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private String tokenFor(String agent, String launch, String mission, String ticket) {
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

    private static SessionClaims claims(String launch) {
        return new SessionClaims(launch, ADA, new MissionId("M-1"), new TicketId("T-2"));
    }

    private Minted minted(String jwt) {
        Ok ok = (Ok) verifier.verify(jwt);
        return new Minted(jwt, ok.jti(), ok.issuer(), ok.expiresAt());
    }

    /** A registered session whose process is bound; the token it carries. */
    private String session(String launch) {
        String jwt = tokenFor("ada", launch, "M-1", "T-2");
        hooks.register(governor, claims(launch), minted(jwt));
        hooks.bindProcess(jwt, 4242);
        return jwt;
    }

    private Authentication call(String jwt) {
        return hooks.authenticate("Bearer " + jwt, CLIENT, SERVER);
    }

    private TokenRefusal refusal(Authentication a) {
        assertThat(a).isInstanceOf(Refused.class);
        return ((Refused) a).reason();
    }

    @Test
    void aVerifiedLiveBoundSessionIsAccepted() {
        String launch = UUID.randomUUID().toString();
        Authentication a = call(session(launch));
        assertThat(a).isInstanceOf(Accepted.class);
        assertThat(((Accepted) a).governor()).isSameAs(governor);
        assertThat(((Accepted) a).claims()).isEqualTo(claims(launch));
    }

    @Test
    void anEndedSessionsTokenIsRefusedAtOnce() {
        String jwt = session(UUID.randomUUID().toString());
        hooks.unregister(jwt);
        Authentication a = call(jwt);
        assertThat(refusal(a)).isEqualTo(TokenRefusal.SESSION_ENDED);
        assertThat(((Refused) a).governor())
                .as("not attributable to a live session")
                .isNull();
    }

    @Test
    void aSignedTokenWithAnotherMissionTicketOrAgentIsRefusedAndAttributed() {
        String launch = UUID.randomUUID().toString();
        String jwt = tokenFor("ada", launch, "M-9", "T-2");
        Minted m = minted(jwt);
        // The live session record says M-1; the token's jti is the one Floor42 minted, its claims differ.
        hooks.register(governor, claims(launch), m);
        hooks.bindProcess(jwt, 4242);
        Authentication a = call(jwt);
        assertThat(refusal(a)).isEqualTo(TokenRefusal.WRONG_MISSION);
        assertThat(((Refused) a).governor()).isSameAs(governor);

        String other = UUID.randomUUID().toString();
        String ticket = tokenFor("ada", other, "M-1", "T-7");
        hooks.register(governor, claims(other), minted(ticket));
        hooks.bindProcess(ticket, 4242);
        assertThat(refusal(call(ticket))).isEqualTo(TokenRefusal.WRONG_TICKET);

        String third = UUID.randomUUID().toString();
        String bob = tokenFor("bob", third, "M-1", "T-2");
        hooks.register(governor, claims(third), minted(bob));
        hooks.bindProcess(bob, 4242);
        assertThat(refusal(call(bob))).isEqualTo(TokenRefusal.WRONG_AGENT);
    }

    @Test
    void aTokenOfTheRightLaunchButAnotherJtiIsSessionEnded() {
        String launch = UUID.randomUUID().toString();
        session(launch);
        Authentication a = call(tokenFor("ada", launch, "M-1", "T-2"));
        assertThat(refusal(a)).isEqualTo(TokenRefusal.SESSION_ENDED);
        assertThat(((Refused) a).governor()).isSameAs(governor);
    }

    @Test
    void aForgedTokenIsNotAttributableToAnySession() {
        String jwt = session(UUID.randomUUID().toString());
        String forged = jwt.substring(0, jwt.length() - 4) + "AAAA";
        Authentication a = call(forged);
        assertThat(a).isInstanceOf(Refused.class);
        assertThat(((Refused) a).governor()).isNull();
        assertThat(call("not.a.jwt")).isInstanceOf(Refused.class);
        assertThat(hooks.authenticate(null, CLIENT, SERVER)).isInstanceOf(Refused.class);
    }

    @Test
    void anAudienceWithAResourceNextToOursIsRefused() {
        String launch = UUID.randomUUID().toString();
        String jwt = session(launch);
        String both = kc.forge(jwt, b -> b.putArray("aud").add("floor42-api").add("crm"));
        hooks.unregister(jwt);
        hooks.register(governor, claims(launch), minted(both));
        hooks.bindProcess(both, 4242);
        assertThat(refusal(call(both))).isEqualTo(TokenRefusal.WRONG_AUDIENCE);
    }

    @Test
    void aRevokedAgentOrDelegationIsRefusedAtOnceAndItsSessionStopped() {
        String jwt = session(UUID.randomUUID().toString());
        revokedAgents = Set.of(ADA);
        assertThat(refusal(call(jwt))).isEqualTo(TokenRefusal.REVOKED);
        assertThat(stopped).hasSize(1);
        revokedAgents = Set.of();
        assertThat(call(jwt)).isInstanceOf(Accepted.class);
        delegationRevoked = true;
        assertThat(refusal(call(jwt))).isEqualTo(TokenRefusal.REVOKED);
        assertThat(stopped).hasSize(2);
    }

    @Test
    void aReGrantDoesNotReviveASessionMintedBeforeTheRevocation() {
        String jwt = session(UUID.randomUUID().toString());
        revokedAt = java.time.Instant.now().plusSeconds(1);
        delegationRevoked = false; // granted again: the delegation is live, the old session stays dead
        assertThat(refusal(call(jwt))).isEqualTo(TokenRefusal.REVOKED);
    }

    @Test
    void twoFailedKeycloakChecksRefuseRunningSessions() {
        String jwt = session(UUID.randomUUID().toString());
        failedChecks = 1;
        assertThat(call(jwt)).isInstanceOf(Accepted.class);
        failedChecks = 2;
        assertThat(refusal(call(jwt))).isEqualTo(TokenRefusal.KEYCLOAK_UNREACHABLE);
    }

    @Test
    void aCallerThatIsNotTheBoundProcessIsRefused() {
        String jwt = session(UUID.randomUUID().toString());
        owner = OptionalLong.of(777);
        assertThat(refusal(call(jwt))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        owner = OptionalLong.empty();
        assertThat(refusal(call(jwt))).as("unknown is refused").isEqualTo(TokenRefusal.WRONG_PROCESS);
        owner = OptionalLong.of(4242);
        assertThat(call(jwt)).isInstanceOf(Accepted.class);
    }

    @Test
    void aCallBeforeTheProcessIsBoundWaitsThenIsWrongProcess() {
        String launch = UUID.randomUUID().toString();
        String jwt = tokenFor("ada", launch, "M-1", "T-2");
        hooks.register(governor, claims(launch), minted(jwt));
        long begin = System.nanoTime();
        assertThat(refusal(call(jwt))).isEqualTo(TokenRefusal.WRONG_PROCESS);
        assertThat((System.nanoTime() - begin) / 1_000_000).isBetween(1_500L, 5_000L);
    }

    @Test
    void theAccessGuardsQuestionIsAboutTheTokenAndTheSessionNotTheProcess() {
        String jwt = session(UUID.randomUUID().toString());
        owner = OptionalLong.of(777);
        assertThat(hooks.isLive(jwt)).isTrue();
        hooks.unregister(jwt);
        assertThat(hooks.isLive(jwt)).isFalse();
        assertThat(lookups.get()).as("no socket lookup for isLive").isZero();
    }

    @Test
    void offModeKeepsTheRandomTokenAndAnUnknownOneHasNoReason() {
        HookRegistry off = new HookRegistry(() -> false, null);
        String token = off.register(governor);
        assertThat(off.authenticate("Bearer " + token, null, null)).isEqualTo(new Accepted(governor, null));
        Authentication unknown = off.authenticate("Bearer nope", null, null);
        assertThat(unknown).isEqualTo(new Refused(null, null, null, null));
        off.unregister(token);
        assertThat(off.isLive(token)).isFalse();
    }
}
