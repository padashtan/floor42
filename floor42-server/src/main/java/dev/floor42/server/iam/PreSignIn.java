package dev.floor42.server.iam;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Binds an OIDC {@code state} to the browser that started the flow: a random secret goes to that browser in a
 * short-lived cookie (HttpOnly, SameSite=Lax so it comes back on Keycloak's redirect, Path=/auth) and only its
 * hash is kept with the pending state. A callback that does not bring the secret did not start in this browser: a
 * local process that obtained a code and a state cannot finish someone else's flow. The cookie name carries the start
 * of the state, so two flows of one browser do not overwrite each other.
 */
final class PreSignIn {

    private static final int MAX_AGE_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PreSignIn() {}

    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Jwts.base64url(b);
    }

    /** What is kept with the pending state: the hash, never the secret. */
    static String hash(String secret) {
        try {
            return Jwts.base64url(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void give(RoutingContext rc, String state, String secret) {
        rc.response()
                .addCookie(Cookie.cookie(name(state), secret)
                        .setHttpOnly(true)
                        .setSameSite(CookieSameSite.LAX)
                        .setPath("/auth")
                        .setMaxAge(MAX_AGE_SECONDS));
    }

    /** True when the request brings the secret whose hash {@code expected} is (constant-time compare). */
    static boolean presented(RoutingContext rc, String state, String expected) {
        Cookie c = rc.request().getCookie(name(state));
        return c != null && IdTokenCheck.sameSecret(expected, hash(c.getValue()));
    }

    /** The hashes of the secrets this browser holds for flows it started and has not finished. */
    static java.util.Set<String> held(RoutingContext rc) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Cookie c : rc.request().cookies()) {
            if (c.getName().startsWith("floor42_pre_")) out.add(hash(c.getValue()));
        }
        return out;
    }

    /**
     * Whether this request is a top-level navigation of the browser, as the Fetch Metadata headers tell (absent headers
     * are a script or an old browser: allowed). An {@code <img>}, a fetch or a frame of another site is not: it could
     * otherwise fill the table of pending sign-ins without the director's knowledge.
     */
    static boolean navigation(RoutingContext rc) {
        String site = rc.request().getHeader("Sec-Fetch-Site");
        String mode = rc.request().getHeader("Sec-Fetch-Mode");
        String dest = rc.request().getHeader("Sec-Fetch-Dest");
        if ("cross-site".equals(site)) return false;
        return (mode == null || "navigate".equals(mode)) && (dest == null || "document".equals(dest));
    }

    private static String name(String state) {
        return "floor42_pre_" + state.substring(0, Math.min(8, state.length())).replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
