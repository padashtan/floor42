package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Verifies the director's {@code id_token}: RS256 only, the issuer's keys, the claims OpenID Connect Core 3.1.3.7 and
 * 2 require ({@code iss}, {@code sub}, {@code aud}, {@code exp}, {@code iat}, nonce, {@code azp} when there are several
 * audiences), 30 s of clock skew. A refusal logs the name of the rule that failed, never the token or a claim value.
 */
final class IdTokenCheck {

    private static final Logger LOG = Logger.getLogger(IdTokenCheck.class);

    private final URI issuer;
    private final String audience;
    private final Jwks jwks;
    private final Clock clock;

    IdTokenCheck(URI issuer, String audience, Jwks jwks, Clock clock) {
        this.issuer = issuer;
        this.audience = audience;
        this.jwks = jwks;
        this.clock = clock;
    }

    /** True only when every check passes; any doubt, a malformed token included, is false. */
    boolean valid(String idToken, String nonce) {
        return claims(idToken, nonce).isPresent();
    }

    /** The claims of an id_token that passed every check, else empty (the failed rule is logged). */
    Optional<JsonNode> claims(String idToken, String nonce) {
        String[] rule = new String[1];
        Optional<JsonNode> out = check(idToken, nonce, rule);
        if (out.isEmpty()) LOG.warnf("ID token refused: rule=%s", rule[0]);
        return out;
    }

    /**
     * The realm roles of the access token that came with the id_token in one code exchange: the id_token and the
     * userinfo carry none (measured on Keycloak 26.8.1, ADR-0081). Checked like the id_token: RS256, the issuer's keys,
     * {@code iss}, the time claims, {@code azp} this client, and the {@code sub} of that id_token. Empty on any doubt.
     */
    Optional<java.util.Set<String>> realmRoles(String accessToken, String subject) {
        String[] rule = new String[1];
        try {
            Optional<Jwts.Parsed> t = signed(accessToken, rule);
            if (t.isEmpty()) return log(rule);
            JsonNode c = t.get().claims();
            String why = !issuer.toString().equals(c.path("iss").asText())
                    ? "iss"
                    : !audience.equals(c.path("azp").asText())
                            ? "azp"
                            : !sameSecret(subject, c.path("sub").asText("")) ? "sub" : timeRule(c);
            if (why != null) {
                rule[0] = why;
                return log(rule);
            }
            java.util.Set<String> roles = new java.util.HashSet<>();
            c.path("realm_access").path("roles").forEach(r -> roles.add(r.asText()));
            return Optional.of(roles);
        } catch (RuntimeException e) {
            // Handled: anything unexpected is "no roles known"; the countersignature is refused.
            rule[0] = "malformed";
            return log(rule);
        }
    }

    /** Whether the verified access token (for this subject) carries the director realm role. */
    boolean director(String accessToken, String subject) {
        return accessToken != null
                && realmRoles(accessToken, subject)
                        .filter(r -> r.contains(Countersigners.DIRECTOR_ROLE))
                        .isPresent();
    }

    /** Same, for a token whose subject is its own: a refreshed login, where no id_token comes along. */
    boolean director(String accessToken) {
        try {
            return director(
                    accessToken, Jwts.parse(accessToken).claims().path("sub").asText(""));
        } catch (RuntimeException e) {
            // Handled: an unreadable token is no director.
            return false;
        }
    }

    private static <T> Optional<T> log(String[] rule) {
        LOG.warnf("Access token refused: rule=%s", rule[0]);
        return Optional.empty();
    }

    /** RS256 only, a known key, a signature that verifies: the parsed token, or empty with the rule that failed. */
    private Optional<Jwts.Parsed> signed(String token, String[] rule) {
        Jwts.Parsed t = Jwts.parse(token);
        JsonNode h = t.header();
        if (!"RS256".equals(h.path("alg").asText()) || h.has("crit")) return refuse(rule, "alg");
        if (!token.substring(token.lastIndexOf('.') + 1).equals(Jwts.base64url(t.signature())))
            return refuse(rule, "signature");
        String kid = h.path("kid").asText("");
        if (kid.isBlank()) return refuse(rule, "kid");
        Optional<PublicKey> key = jwks.key(kid);
        if (key.isEmpty()) return refuse(rule, "kid");
        if (!Jwts.verify(t, key.get())) return refuse(rule, "signature");
        return Optional.of(t);
    }

    private Optional<JsonNode> check(String idToken, String nonce, String[] rule) {
        try {
            Optional<Jwts.Parsed> parsed = signed(idToken, rule);
            if (parsed.isEmpty()) return Optional.empty();
            JsonNode c = parsed.get().claims();
            if (!issuer.toString().equals(c.path("iss").asText())) return refuse(rule, "iss");
            if (!hasSubject(c.path("sub"))) return refuse(rule, "sub");
            if (!hasAudience(c.path("aud"))) return refuse(rule, "aud");
            JsonNode aud = c.path("aud");
            if (aud.isArray() && aud.size() > 1 && !c.has("azp")) return refuse(rule, "azp");
            if (c.has("azp") && !audience.equals(c.path("azp").asText())) return refuse(rule, "azp");
            if (!sameSecret(nonce, c.path("nonce").asText(""))) return refuse(rule, "nonce");
            String time = timeRule(c);
            return time == null ? Optional.of(c) : refuse(rule, time);
        } catch (RuntimeException e) {
            // Handled: anything unexpected is "not a valid id_token"; the sign-in is refused.
            return refuse(rule, "malformed");
        }
    }

    private static <T> Optional<T> refuse(String[] rule, String name) {
        rule[0] = name;
        return Optional.empty();
    }

    /** A non-blank string of at most 255 characters (Core 2). */
    private static boolean hasSubject(JsonNode sub) {
        return sub.isTextual() && !sub.asText().isBlank() && sub.asText().length() <= 255;
    }

    /** A string, or a non-empty array of strings, one of them this client: any other shape is refused. */
    private boolean hasAudience(JsonNode aud) {
        if (aud.isTextual()) return audience.equals(aud.asText());
        if (!aud.isArray() || aud.isEmpty()) return false;
        boolean found = false;
        for (JsonNode a : aud) {
            if (!a.isTextual()) return false;
            found |= audience.equals(a.asText());
        }
        return found;
    }

    /** The name of the failed time rule, or null. */
    private String timeRule(JsonNode c) {
        if (!c.path("exp").isIntegralNumber()) return "exp";
        Instant now = clock.instant();
        if (now.isAfter(Instant.ofEpochSecond(c.path("exp").asLong()).plus(AgentTokenVerifier.SKEW))) return "exp";
        if (!c.path("iat").isIntegralNumber()) return "iat";
        for (String claim : new String[] {"iat", "nbf"}) {
            if (c.has(claim) && !c.path(claim).isIntegralNumber()) return claim;
            if (c.has(claim)
                    && Instant.ofEpochSecond(c.path(claim).asLong())
                            .minus(AgentTokenVerifier.SKEW)
                            .isAfter(now)) return claim;
        }
        return null;
    }

    static boolean sameSecret(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
