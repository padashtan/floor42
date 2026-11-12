package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** What the browser flows against Keycloak (sign-in, step-up approval) and its client share. */
final class OidcFlow {

    private OidcFlow() {}

    /** The PKCE challenge of a verifier: its SHA-256, base64url. */
    static String challenge(String verifier) {
        try {
            return Jwts.base64url(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A query or form value. */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A JSON answer with a code and a message, never anything of what Keycloak said. */
    static void reply(RoutingContext rc, ObjectMapper json, int status, String code, String message) {
        rc.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(json.createObjectNode()
                        .put("code", code)
                        .put("message", message)
                        .toString());
    }
}
