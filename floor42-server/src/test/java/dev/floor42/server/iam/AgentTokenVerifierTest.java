package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.domain.AgentId;
import dev.floor42.server.iam.AgentTokenVerifier.Ok;
import dev.floor42.server.iam.AgentTokenVerifier.Refused;
import dev.floor42.server.iam.AgentTokenVerifier.Verified;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTokenVerifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADA = "floor42-agent-ada";

    private FakeKeycloak kc;
    private Instant now;
    private AgentTokenVerifier verifier;
    private Jwks jwks;

    @BeforeEach
    void start() {
        kc = FakeKeycloak.start("floor42");
        now = Instant.now();
        Clock clock = new Clock() {
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId z) {
                return this;
            }

            public Instant instant() {
                return now;
            }
        };
        jwks = new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
        verifier = new AgentTokenVerifier(kc.issuer(), "floor42-api", jwks, clock);
    }

    @AfterEach
    void stop() {
        kc.close();
    }

    private String token() {
        return kc.exchangeForTest(
                ADA,
                kc.directorToken("director"),
                Map.of(
                        "audience",
                        "floor42-api",
                        "floor42_launch",
                        UUID.randomUUID().toString(),
                        "floor42_mission",
                        "M-1",
                        "floor42_ticket",
                        "T-2"));
    }

    private String forged(Consumer<ObjectNode> edit) {
        return kc.forge(token(), edit);
    }

    private TokenRefusal refusal(String jwt) {
        Verified v = verifier.verify(jwt);
        assertThat(v).isInstanceOf(Refused.class);
        return ((Refused) v).reason();
    }

    @Test
    void aFreshTokenIsOkWithItsClaims() {
        Verified v = verifier.verify(token());
        assertThat(v).isInstanceOf(Ok.class);
        Ok ok = (Ok) v;
        assertThat(ok.claims().agent()).isEqualTo(new AgentId("ada"));
        assertThat(ok.claims().mission().value()).isEqualTo("M-1");
        assertThat(ok.claims().ticket().value()).isEqualTo("T-2");
        assertThat(ok.azp()).isEqualTo(ADA).isEqualTo(ok.actClientId());
        assertThat(ok.issuer()).isEqualTo(kc.issuer().toString());
    }

    @Test
    void expiredBy29sIsAcceptedBy31sRefused() {
        assertThat(verifier.verify(forged(b -> b.put("exp", now.minusSeconds(29).getEpochSecond()))))
                .isInstanceOf(Ok.class);
        assertThat(refusal(forged(b -> b.put("exp", now.minusSeconds(31).getEpochSecond()))))
                .isEqualTo(TokenRefusal.EXPIRED);
    }

    @Test
    void issuedInTheFutureBy31sIsRefused() {
        assertThat(refusal(forged(b -> b.put("iat", now.plusSeconds(31).getEpochSecond()))))
                .isEqualTo(TokenRefusal.EXPIRED);
        assertThat(verifier.verify(forged(b -> b.put("iat", now.plusSeconds(29).getEpochSecond()))))
                .isInstanceOf(Ok.class);
    }

    @Test
    void notBeforeInTheFutureBy31sIsRefused() {
        assertThat(refusal(forged(b -> b.put("nbf", now.plusSeconds(31).getEpochSecond()))))
                .isEqualTo(TokenRefusal.EXPIRED);
    }

    @Test
    void algNoneIsRefused() {
        String[] p = token().split("\\.");
        String header = Jwts.base64url(
                "{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"fake-1\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(refusal(header + "." + p[1] + ".")).isEqualTo(TokenRefusal.BAD_SIGNATURE);
        assertThat(refusal(header + "." + p[1] + "." + p[2])).isEqualTo(TokenRefusal.BAD_SIGNATURE);
    }

    @Test
    void hs256SignedWithThePublicKeyIsRefused() throws Exception {
        String t = token();
        String[] p = t.split("\\.");
        byte[] secret = jwks.key("fake-1").orElseThrow().getEncoded();
        String header = Jwts.base64url(
                "{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"fake-1\"}".getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String input = header + "." + p[1];
        String sig = Jwts.base64url(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        assertThat(refusal(input + "." + sig)).isEqualTo(TokenRefusal.BAD_SIGNATURE);
    }

    @Test
    void aTamperedPayloadIsRefused() {
        String[] p = token().split("\\.");
        ObjectNode body = JSON.createObjectNode().put("iss", kc.issuer().toString());
        String evil = Jwts.base64url(body.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(refusal(p[0] + "." + evil + "." + p[2])).isEqualTo(TokenRefusal.BAD_SIGNATURE);
    }

    @Test
    void anotherAudienceIsRefused() {
        assertThat(refusal(forged(b -> b.put("aud", "account")))).isEqualTo(TokenRefusal.WRONG_AUDIENCE);
        String brokerToken = kc.exchangeForTest(ADA, kc.directorToken("d"), Map.of("audience", "res-x"));
        assertThat(refusal(brokerToken)).isEqualTo(TokenRefusal.WRONG_AUDIENCE);
    }

    @Test
    void theSimulatedIssuerIsNotTheRealOne() {
        var sim = new SimulatedIssuer();
        var claims = new SessionClaims(
                UUID.randomUUID().toString(),
                new AgentId("ada"),
                new dev.floor42.domain.MissionId("M-1"),
                new dev.floor42.domain.TicketId("T-2"));
        assertThat(refusal(sim.mint(claims, Duration.ofHours(1)))).isEqualTo(TokenRefusal.ISSUER_UNKNOWN);
    }

    @Test
    void actAndAzpMustNameTheSameAgent() {
        assertThat(refusal(forged(b -> ((ObjectNode) b.get("act")).put("client_id", "floor42-agent-bob"))))
                .isEqualTo(TokenRefusal.WRONG_AGENT);
        assertThat(refusal(forged(b -> b.remove("act")))).isEqualTo(TokenRefusal.WRONG_AGENT);
    }

    @Test
    void theLaunchMissionAndTicketMustBeWellFormed() {
        assertThat(refusal(forged(b -> ((ObjectNode) b.get("floor42")).put("mission", "X-1"))))
                .isEqualTo(TokenRefusal.WRONG_MISSION);
        assertThat(refusal(forged(b -> ((ObjectNode) b.get("floor42")).put("ticket", "T-x"))))
                .isEqualTo(TokenRefusal.WRONG_TICKET);
        assertThat(refusal(forged(b -> ((ObjectNode) b.get("floor42")).put("launch", "L-1"))))
                .isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(forged(b -> b.remove("floor42")))).isEqualTo(TokenRefusal.MALFORMED);
    }

    @Test
    void garbageIsMalformedAndNeverThrows() {
        assertThat(refusal("a.b")).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal("")).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(null)).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal("!!.!!.!!")).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(forged(b -> b.put("exp", "soon")))).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(forged(b -> b.put("floor42", 7)))).isEqualTo(TokenRefusal.MALFORMED);
    }

    @Test
    void unknownKidRefreshesOnceThenAccepts() {
        assertThat(verifier.verify(token())).isInstanceOf(Ok.class);
        kc.rotateKey();
        assertThat(verifier.verify(token())).isInstanceOf(Ok.class);
        assertThat(certsFetches()).isEqualTo(2);
    }

    @Test
    void unknownKidAfterRefreshIsRefusedAndNotRefetchedWithin30s() {
        assertThat(verifier.verify(token())).isInstanceOf(Ok.class);
        String[] p = token().split("\\.");
        String header = Jwts.base64url(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"forged\"}".getBytes(StandardCharsets.UTF_8));
        String bad = header + "." + p[1] + "." + p[2];
        assertThat(refusal(bad)).isEqualTo(TokenRefusal.BAD_SIGNATURE);
        assertThat(refusal(bad)).isEqualTo(TokenRefusal.BAD_SIGNATURE);
        assertThat(certsFetches()).isEqualTo(2);
    }

    @Test
    void anUnreachableIssuerRefusesAnUnknownKidButKnownKeysStillWork() {
        assertThat(verifier.verify(token())).isInstanceOf(Ok.class);
        kc.rotateKey();
        String rotated = token();
        kc.down(true);
        assertThat(refusal(rotated)).isEqualTo(TokenRefusal.KEYCLOAK_UNREACHABLE);
        assertThat(jwks.reachable()).isFalse();
        kc.down(false);
        now = now.plus(Jwks.MIN_REFETCH);
        assertThat(verifier.verify(rotated)).isInstanceOf(Ok.class);
        assertThat(jwks.reachable()).isTrue();
    }

    @Test
    void aRealRotationRightAfterAForgedKidWaitsForTheWindow() {
        assertThat(verifier.verify(token())).isInstanceOf(Ok.class);
        String[] p = token().split("\\.");
        assertThat(refusal(withHeader("{\"alg\":\"RS256\",\"kid\":\"forged\"}", p)))
                .isEqualTo(TokenRefusal.BAD_SIGNATURE);
        kc.rotateKey();
        String rotated = token();
        assertThat(refusal(rotated)).isEqualTo(TokenRefusal.BAD_SIGNATURE);
        assertThat(certsFetches()).isEqualTo(2);
        now = now.plus(Jwks.MIN_REFETCH);
        assertThat(verifier.verify(rotated)).isInstanceOf(Ok.class);
    }

    @Test
    void refreshThenVerifyDoesNotFetchAgain() {
        jwks.refresh();
        assertThat(verifier.verify(token())).isInstanceOf(Ok.class);
        assertThat(certsFetches()).isEqualTo(1);
    }

    @Test
    void theAgentPrefixMustMatchTheIssuer() {
        assertThat(refusal(forged(b -> setClient(b, "simulated-ada")))).isEqualTo(TokenRefusal.WRONG_AGENT);
        assertThat(refusal(forged(b -> setClient(b, "ada")))).isEqualTo(TokenRefusal.WRONG_AGENT);
        assertThat(refusal(forged(b -> setClient(b, "floor42-agent-")))).isEqualTo(TokenRefusal.WRONG_AGENT);
    }

    @Test
    void theSimulatedIssuerNeedsTheSimulatedPrefix() throws Exception {
        var pair = java.security.KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        var keys = pair.generateKeyPair();
        try (var server = new JwksServer(keys.getPublic(), "own-1")) {
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var v = new AgentTokenVerifier(
                    URI.create(SimulatedIssuer.ISSUER),
                    "floor42-api",
                    new Jwks(server.uri(), HttpClient.newHttpClient(), clock),
                    clock);
            assertThat(v.verify(own(keys.getPrivate(), "simulated-ada"))).isInstanceOf(Ok.class);
            Verified wrong = v.verify(own(keys.getPrivate(), "floor42-agent-ada"));
            assertThat(wrong).isInstanceOf(Refused.class);
            assertThat(((Refused) wrong).reason()).isEqualTo(TokenRefusal.WRONG_AGENT);
        }
    }

    @Test
    void aTokenLivingLongerThanTheConfiguredMaximumIsRefusedWhateverKeycloakIssued() throws Exception {
        var pair = java.security.KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        var keys = pair.generateKeyPair();
        try (var server = new JwksServer(keys.getPublic(), "own-1")) {
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            var jwks = new Jwks(server.uri(), HttpClient.newHttpClient(), clock);
            String jwt = own(keys.getPrivate(), "simulated-ada"); // lives 600 s
            var uri = URI.create(SimulatedIssuer.ISSUER);
            assertThat(new AgentTokenVerifier(uri, "floor42-api", jwks, clock, Duration.ofSeconds(570)).verify(jwt))
                    .isInstanceOf(Ok.class);
            Verified tooLong =
                    new AgentTokenVerifier(uri, "floor42-api", jwks, clock, Duration.ofSeconds(569)).verify(jwt);
            assertThat(tooLong).isInstanceOf(Refused.class);
        }
    }

    private String own(java.security.PrivateKey key, String client) {
        ObjectNode h =
                JSON.createObjectNode().put("alg", "RS256").put("typ", "JWT").put("kid", "own-1");
        ObjectNode b = JSON.createObjectNode()
                .put("iss", SimulatedIssuer.ISSUER)
                .put("azp", client)
                .put("aud", "floor42-api")
                .put("exp", now.plusSeconds(600).getEpochSecond())
                .put("jti", "j-1");
        b.putObject("act").put("client_id", client);
        b.putObject("floor42")
                .put("launch", UUID.randomUUID().toString())
                .put("mission", "M-1")
                .put("ticket", "T-1");
        return Jwts.sign(h, b, key);
    }

    private static void setClient(ObjectNode b, String client) {
        b.put("azp", client);
        ((ObjectNode) b.get("act")).put("client_id", client);
    }

    private static String withHeader(String header, String[] p) {
        return Jwts.base64url(header.getBytes(StandardCharsets.UTF_8)) + "." + p[1] + "." + p[2];
    }

    @Test
    void aMissingOrBlankKidOrACritHeaderIsMalformed() {
        String[] p = token().split("\\.");
        assertThat(refusal(withHeader("{\"alg\":\"RS256\"}", p))).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(withHeader("{\"alg\":\"RS256\",\"kid\":\" \"}", p))).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(withHeader("{\"alg\":\"RS256\",\"kid\":7}", p))).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(withHeader("{\"alg\":\"RS256\",\"kid\":\"fake-1\",\"crit\":[\"x\"],\"x\":1}", p)))
                .isEqualTo(TokenRefusal.MALFORMED);
    }

    @Test
    void anAudienceThatIsAnObjectIsRefused() {
        assertThat(refusal(forged(b -> b.putObject("aud").put("x", "floor42-api"))))
                .isEqualTo(TokenRefusal.WRONG_AUDIENCE);
        assertThat(verifier.verify(forged(b -> b.putArray("aud").add("other").add("floor42-api"))))
                .isInstanceOf(Ok.class);
    }

    @Test
    void iatAndNbfMustBeIntegers() {
        assertThat(refusal(forged(b -> b.put("iat", "now")))).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(refusal(forged(b -> b.put("nbf", 1.5)))).isEqualTo(TokenRefusal.MALFORMED);
    }

    @Test
    void theJtiIsReportedOnlyAfterTheSignaturePassed() {
        String[] p = token().split("\\.");
        String evilJson = "{\"jti\":\"j\",\"iss\":\"" + kc.issuer() + "\"}";
        String evil = Jwts.base64url(evilJson.getBytes(StandardCharsets.UTF_8));
        assertThat(((Refused) verifier.verify(p[0] + "." + evil + "." + p[2])).jti())
                .isNull();
        Refused expired = (Refused)
                verifier.verify(forged(b -> b.put("exp", now.minusSeconds(99).getEpochSecond())));
        assertThat(expired.jti()).isNotNull();
    }

    @Test
    void aSignatureWithAnotherSpellingIsMalformed() {
        String t = token();
        assertThat(refusal(t + "==")).isEqualTo(TokenRefusal.MALFORMED);
        assertThat(verifier.verify(t)).isInstanceOf(Ok.class);
    }

    private long certsFetches() {
        return kc.requests().stream()
                .filter(r -> r.get("_path").endsWith("/certs"))
                .count();
    }
}
