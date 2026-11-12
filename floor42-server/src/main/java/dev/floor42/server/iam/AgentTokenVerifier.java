package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import java.net.URI;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Checks an agent token locally and fails closed: every path that cannot fully verify answers Refused. */
public final class AgentTokenVerifier {

    public static final Duration SKEW = Duration.ofSeconds(30);
    public static final String AUDIENCE = "floor42-api";

    private static final Pattern MISSION = Pattern.compile("M-\\d+");
    private static final Pattern TICKET = Pattern.compile("T-\\d+");

    public sealed interface Verified permits Ok, Refused {}

    public record Ok(
            String jti,
            String issuer,
            String azp,
            String actClientId,
            SessionClaims claims,
            Instant expiresAt,
            JsonNode header,
            JsonNode payload)
            implements Verified {}

    public record Refused(TokenRefusal reason, String jti) implements Verified {}

    private final URI issuer;
    private final String expectedAudience;
    private final Jwks jwks;
    private final Clock clock;
    private final Duration maxLife;

    public AgentTokenVerifier(URI issuer, String expectedAudience, Jwks jwks, Clock clock) {
        this(issuer, expectedAudience, jwks, clock, Duration.ofHours(8));
    }

    /** {@code maxLife}: the longest token life Floor42 accepts, whatever Keycloak is configured to issue. */
    public AgentTokenVerifier(URI issuer, String expectedAudience, Jwks jwks, Clock clock, Duration maxLife) {
        this.maxLife = maxLife;
        this.issuer = issuer;
        this.expectedAudience = expectedAudience;
        this.jwks = jwks;
        this.clock = clock;
    }

    public Verified verify(String jwt) {
        String jti = null;
        try {
            Jwts.Parsed t = Jwts.parse(jwt);
            if (!canonicalSignature(jwt, t)) return new Refused(TokenRefusal.MALFORMED, null);
            Optional<TokenRefusal> bad = beforeSignature(t);
            if (bad.isEmpty()) bad = signature(t);
            // The jti is only trusted, and only reported, once the signature has passed.
            if (bad.isPresent()) return new Refused(bad.get(), null);
            JsonNode c = t.claims();
            jti = c.path("jti").isTextual() ? c.path("jti").asText() : null;
            bad = afterSignature(c);
            return bad.isPresent() ? new Refused(bad.get(), jti) : ok(t, jti);
        } catch (RuntimeException e) {
            // Handled: anything unexpected, a bad claim shape included, is a refusal and never an Ok.
            return new Refused(TokenRefusal.MALFORMED, jti);
        }
    }

    /** The signature segment must be exactly the unpadded base64url of its bytes: one token, one spelling. */
    private static boolean canonicalSignature(String jwt, Jwts.Parsed t) {
        return jwt.substring(jwt.lastIndexOf('.') + 1).equals(Jwts.base64url(t.signature()));
    }

    private Optional<TokenRefusal> beforeSignature(Jwts.Parsed t) {
        JsonNode h = t.header();
        if (!"RS256".equals(h.path("alg").asText())) return Optional.of(TokenRefusal.BAD_SIGNATURE);
        if (h.has("crit") || (h.has("typ") && !"JWT".equals(h.path("typ").asText())))
            return Optional.of(TokenRefusal.MALFORMED);
        if (!h.path("kid").isTextual() || h.path("kid").asText().isBlank()) return Optional.of(TokenRefusal.MALFORMED);
        if (!issuer.toString().equals(t.claims().path("iss").asText())) return Optional.of(TokenRefusal.ISSUER_UNKNOWN);
        return Optional.empty();
    }

    private Optional<TokenRefusal> afterSignature(JsonNode c) {
        if (!c.path("exp").isIntegralNumber() || !c.path("jti").isTextual()) return Optional.of(TokenRefusal.MALFORMED);
        if ((c.has("iat") && !c.path("iat").isIntegralNumber())
                || (c.has("nbf") && !c.path("nbf").isIntegralNumber())) return Optional.of(TokenRefusal.MALFORMED);
        if (timeRefused(c)) return Optional.of(TokenRefusal.EXPIRED);
        if (!hasAudience(c.path("aud"))) return Optional.of(TokenRefusal.WRONG_AUDIENCE);
        return claimsRefusal(c);
    }

    private Optional<TokenRefusal> signature(Jwts.Parsed t) {
        String kid = t.header().path("kid").asText("");
        Optional<PublicKey> key = jwks.key(kid);
        if (key.isEmpty())
            return Optional.of(jwks.reachable() ? TokenRefusal.BAD_SIGNATURE : TokenRefusal.KEYCLOAK_UNREACHABLE);
        return Jwts.verify(t, key.get()) ? Optional.empty() : Optional.of(TokenRefusal.BAD_SIGNATURE);
    }

    private boolean timeRefused(JsonNode c) {
        Instant now = clock.instant();
        Instant exp = Instant.ofEpochSecond(c.path("exp").asLong());
        if (now.isAfter(exp.plus(SKEW))) return true;
        Instant from = c.has("iat") ? Instant.ofEpochSecond(c.path("iat").asLong()) : now;
        if (Duration.between(from, exp).compareTo(maxLife.plus(SKEW)) > 0) return true;
        if (c.has("iat")
                && Instant.ofEpochSecond(c.path("iat").asLong()).minus(SKEW).isAfter(now)) return true;
        return c.has("nbf")
                && Instant.ofEpochSecond(c.path("nbf").asLong()).minus(SKEW).isAfter(now);
    }

    /** A session token is for Floor42's API alone: {@code aud} is exactly it, or an array of exactly it. */
    public static boolean onlyOurAudience(JsonNode aud) {
        if (aud.isTextual()) return AUDIENCE.equals(aud.asText());
        return aud.isArray() && aud.size() == 1 && AUDIENCE.equals(aud.get(0).asText());
    }

    private boolean hasAudience(JsonNode aud) {
        if (aud.isTextual()) return expectedAudience.equals(aud.asText());
        if (!aud.isArray()) return false;
        for (JsonNode a : aud) if (a.isTextual() && expectedAudience.equals(a.asText())) return true;
        return false;
    }

    private Optional<TokenRefusal> claimsRefusal(JsonNode c) {
        String azp = c.path("azp").asText("");
        String actClient = c.path("act").path("client_id").asText("");
        if (azp.isEmpty() || !azp.equals(actClient) || agentOf(azp) == null)
            return Optional.of(TokenRefusal.WRONG_AGENT);
        JsonNode f = c.path("floor42");
        if (!f.isObject() || !isUuid(f.path("launch").asText(""))) return Optional.of(TokenRefusal.MALFORMED);
        if (!MISSION.matcher(f.path("mission").asText("")).matches()) return Optional.of(TokenRefusal.WRONG_MISSION);
        if (!TICKET.matcher(f.path("ticket").asText("")).matches()) return Optional.of(TokenRefusal.WRONG_TICKET);
        return Optional.empty();
    }

    private static boolean isUuid(String s) {
        try {
            return UUID.fromString(s).toString().equalsIgnoreCase(s);
        } catch (IllegalArgumentException e) {
            // Handled: a launch that is not a UUID is reported as a malformed token by the caller.
            return false;
        }
    }

    private Verified ok(Jwts.Parsed t, String jti) {
        JsonNode c = t.claims();
        String azp = c.path("azp").asText();
        JsonNode f = c.path("floor42");
        SessionClaims claims = new SessionClaims(
                f.path("launch").asText(),
                new AgentId(agentOf(azp)),
                new MissionId(f.path("mission").asText()),
                new TicketId(f.path("ticket").asText()));
        return new Ok(
                jti,
                c.path("iss").asText(),
                azp,
                c.path("act").path("client_id").asText(),
                claims,
                Instant.ofEpochSecond(c.path("exp").asLong()),
                t.header(),
                c);
    }

    /** The agent id after the prefix of this issuer's clients, or null when the client has another prefix. */
    private String agentOf(String azp) {
        String prefix = SimulatedIssuer.ISSUER.equals(issuer.toString()) ? "simulated-" : "floor42-agent-";
        if (!azp.startsWith(prefix) || azp.length() == prefix.length()) return null;
        return azp.substring(prefix.length());
    }
}
