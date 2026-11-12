package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The id_token proof of the director: every wrong claim, header or signature is refused, a good token passes. */
class IdTokenCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final URI ISSUER = URI.create("http://idp.test/realms/floor42");
    private static final String NONCE = "n-123";

    private static KeyPair pair;
    private static KeyPair other;
    private static JwksServer server;
    private static IdTokenCheck check;

    @BeforeAll
    static void start() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        pair = g.generateKeyPair();
        other = g.generateKeyPair();
        server = new JwksServer(pair.getPublic(), "k1");
        Jwks jwks = new Jwks(server.uri(), HttpClient.newHttpClient(), CLOCK);
        check = new IdTokenCheck(ISSUER, "floor42-desk", jwks, CLOCK);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static ObjectNode goodClaims() {
        return JSON.createObjectNode()
                .put("iss", ISSUER.toString())
                .put("aud", "floor42-desk")
                .put("azp", "floor42-desk")
                .put("nonce", NONCE)
                .put("sub", "director")
                .put("iat", NOW.getEpochSecond() - 5)
                .put("exp", NOW.getEpochSecond() + 300);
    }

    private static ObjectNode header(String alg) {
        return JSON.createObjectNode().put("alg", alg).put("typ", "JWT").put("kid", "k1");
    }

    private static String token(Consumer<ObjectNode> edit) {
        ObjectNode c = goodClaims();
        edit.accept(c);
        return Jwts.sign(header("RS256"), c, pair.getPrivate());
    }

    private static boolean valid(String t) {
        return check.valid(t, NONCE);
    }

    private static String b64(String s) {
        return Jwts.base64url(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aValidTokenIsAcceptedAndItsClaimsReturned() {
        String t = token(c -> {});
        assertThat(valid(t)).isTrue();
        assertThat(check.claims(t, NONCE).orElseThrow().path("sub").asText()).isEqualTo("director");
        assertThat(valid(token(c -> c.putArray("aud").add("other").add("floor42-desk"))))
                .isTrue();
    }

    @Test
    void wrongIssuerIsRefused() {
        assertThat(valid(token(c -> c.put("iss", "http://evil.test/realms/floor42"))))
                .isFalse();
        assertThat(valid(token(c -> c.remove("iss")))).isFalse();
    }

    @Test
    void wrongAudienceIsRefused() {
        assertThat(valid(token(c -> c.put("aud", "floor42-api")))).isFalse();
        assertThat(valid(token(c -> c.putArray("aud").add("a").add("b")))).isFalse();
        assertThat(valid(token(c -> c.remove("aud")))).isFalse();
    }

    @Test
    void anAuthorizedPartyOtherThanTheClientIsRefused() {
        assertThat(valid(token(c -> c.put("azp", "floor42-api")))).isFalse();
        assertThat(valid(token(c -> {
                    c.putArray("aud").add("floor42-desk").add("floor42-api");
                    c.put("azp", "floor42-api");
                })))
                .isFalse();
    }

    @Test
    void severalAudiencesNeedAnAuthorizedParty() {
        assertThat(valid(token(c -> {
                    c.putArray("aud").add("other").add("floor42-desk");
                    c.remove("azp");
                })))
                .isFalse();
        assertThat(valid(token(c -> c.remove("azp")))).isTrue();
    }

    @Test
    void anAudienceThatIsNotAStringOrAnArrayOfStringsIsRefused() {
        assertThat(valid(token(c -> c.putObject("aud").put("x", "floor42-desk"))))
                .isFalse();
        assertThat(valid(token(c -> c.putArray("aud").add("floor42-desk").add(7))))
                .isFalse();
        assertThat(valid(token(c -> c.putArray("aud")))).isFalse();
        assertThat(valid(token(c -> c.put("aud", 7)))).isFalse();
    }

    @Test
    void aSingleAudienceInAnArrayNeedsNoAuthorizedParty() {
        assertThat(valid(token(c -> {
                    c.putArray("aud").add("floor42-desk");
                    c.remove("azp");
                })))
                .isTrue();
    }

    @Test
    void aMissingOrMalformedSubjectIsRefused() {
        assertThat(valid(token(c -> c.remove("sub")))).isFalse();
        assertThat(valid(token(c -> c.put("sub", "")))).isFalse();
        assertThat(valid(token(c -> c.put("sub", 7)))).isFalse();
        assertThat(valid(token(c -> c.put("sub", "x".repeat(256))))).isFalse();
        assertThat(valid(token(c -> c.put("sub", "x".repeat(255))))).isTrue();
    }

    @Test
    void aMissingIssuedAtIsRefused() {
        assertThat(valid(token(c -> c.remove("iat")))).isFalse();
    }

    @Test
    void wrongOrMissingNonceIsRefused() {
        assertThat(valid(token(c -> c.put("nonce", "other")))).isFalse();
        assertThat(valid(token(c -> c.remove("nonce")))).isFalse();
        assertThat(check.valid(token(c -> {}), "")).isFalse();
    }

    @Test
    void expiredAndNotYetValidAreRefused() {
        assertThat(valid(token(c -> c.put("exp", NOW.getEpochSecond() - 120)))).isFalse();
        assertThat(valid(token(c -> c.remove("exp")))).isFalse();
        assertThat(valid(token(c -> c.put("exp", "soon")))).isFalse();
        assertThat(valid(token(c -> c.put("nbf", NOW.getEpochSecond() + 120)))).isFalse();
        assertThat(valid(token(c -> c.put("iat", NOW.getEpochSecond() + 120)))).isFalse();
        assertThat(valid(token(c -> c.put("iat", "now")))).isFalse();
        // Inside the 30 s of skew, both ways.
        assertThat(valid(token(c -> c.put("exp", NOW.getEpochSecond() - 10)))).isTrue();
        assertThat(valid(token(c -> c.put("nbf", NOW.getEpochSecond() + 10)))).isTrue();
    }

    @Test
    void anUnexpectedAlgorithmIsRefused() {
        ObjectNode c = goodClaims();
        assertThat(valid(Jwts.sign(header("none"), c, pair.getPrivate()))).isFalse();
        assertThat(valid(Jwts.sign(header("HS256"), c, pair.getPrivate()))).isFalse();
        assertThat(valid(Jwts.sign(header("RS512"), c, pair.getPrivate()))).isFalse();
        // alg none the way an attacker writes it: no signature at all.
        assertThat(valid(b64(header("none").toString()) + "." + b64(c.toString()) + "."))
                .isFalse();
        ObjectNode crit = header("RS256");
        crit.putArray("crit").add("exp");
        assertThat(valid(Jwts.sign(crit, c, pair.getPrivate()))).isFalse();
    }

    @Test
    void aBadSignatureOrUnknownKeyIsRefused() {
        assertThat(valid(Jwts.sign(header("RS256"), goodClaims(), other.getPrivate())))
                .isFalse();
        assertThat(valid(Jwts.sign(header("RS256").put("kid", "nope"), goodClaims(), pair.getPrivate())))
                .isFalse();
        ObjectNode noKid = header("RS256");
        noKid.remove("kid");
        assertThat(valid(Jwts.sign(noKid, goodClaims(), pair.getPrivate()))).isFalse();
        // Payload swapped after signing.
        String[] p = token(c -> {}).split("\\.");
        assertThat(valid(p[0] + "." + b64(goodClaims().put("sub", "mallory").toString()) + "." + p[2]))
                .isFalse();
    }

    @Test
    void garbageIsRefusedNotThrown() {
        assertThat(valid("")).isFalse();
        assertThat(valid("a.b.c")).isFalse();
        assertThat(valid("not a jwt")).isFalse();
    }
}
