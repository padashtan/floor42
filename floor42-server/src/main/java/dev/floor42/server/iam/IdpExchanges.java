package dev.floor42.server.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The last exchanges between Floor42 and Keycloak, for the director to watch: what was asked, what came back, how long
 * it took. In memory only, the newest {@link #CAPACITY}, lost at a restart. Nothing secret is kept: of a form only the
 * fields in {@link #SHOWN_FIELDS} keep their value (a secret, a password, a code or a token shows as {@code •••}), no
 * header at all, and of an answer only its non-secret fields and the claims of a token in {@link #SHOWN_CLAIMS}, never
 * the token itself nor its signature.
 */
@ApplicationScoped
public class IdpExchanges {

    static final int CAPACITY = 300;
    static final String MASK = "•••";

    /** Form fields whose values say what was asked and are not secret. */
    static final Set<String> SHOWN_FIELDS = Set.of(
            "grant_type",
            "audience",
            "requested_token_type",
            "subject_token_type",
            "scope",
            "client_id",
            "token_type_hint",
            "redirect_uri");

    /** Answer fields kept as they are. */
    private static final Set<String> SHOWN_ANSWER = Set.of(
            "token_type",
            "expires_in",
            "refresh_expires_in",
            "scope",
            "issued_token_type",
            "active",
            "error",
            "clientId",
            "enabled",
            "client_id",
            "sub",
            "exp");

    /** Claims of a token in an answer that say who it is for and on whose behalf. */
    static final Set<String> SHOWN_CLAIMS =
            Set.of("iss", "sub", "azp", "aud", "act", "exp", "iat", "scope", "floor42", "preferred_username");

    private static final Set<String> TOKEN_FIELDS = Set.of("access_token", "refresh_token", "id_token");

    /**
     * @param at when the request left
     * @param op what Floor42 was doing ("token exchange", "rotate secret"...)
     * @param method the HTTP method
     * @param path the URL's path, never its query
     * @param status the HTTP status, 0 when no answer came
     * @param millis from the request to the answer
     * @param request the form's fields, secret values masked; empty for JSON or no body
     * @param answer the answer's non-secret fields, and {@code access_token.<claim>} for the claims kept
     * @param error what went wrong when no answer came (network, timeout), else null
     */
    public record Exchange(
            Instant at,
            String op,
            String method,
            String path,
            int status,
            long millis,
            Map<String, String> request,
            Map<String, String> answer,
            String error) {
        public Exchange {
            answer = answer == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(answer));
            request = request == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(request));
        }
    }

    private final Deque<Exchange> last = new ArrayDeque<>();
    private final ObjectMapper json = new ObjectMapper();

    /** The newest first. */
    public synchronized List<Exchange> list() {
        List<Exchange> out = new ArrayList<>(last);
        java.util.Collections.reverse(out);
        return out;
    }

    synchronized void add(Exchange e) {
        if (last.size() == CAPACITY) last.removeFirst();
        last.addLast(e);
    }

    /** Records one exchange; {@code form} is the url-encoded body Floor42 sent, or null. */
    void record(
            Instant at,
            String op,
            String method,
            URI uri,
            String form,
            int status,
            long millis,
            String body,
            String error) {
        add(new Exchange(at, op, method, uri.getPath(), status, millis, maskForm(form), answer(body), error));
    }

    static Map<String, String> maskForm(String form) {
        Map<String, String> out = new LinkedHashMap<>();
        if (form == null || form.isBlank()) return out;
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, SHOWN_FIELDS.contains(key) ? clip(value) : MASK);
        }
        return out;
    }

    Map<String, String> answer(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return out;
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            // Handled: an answer that is not JSON keeps nothing; its status is recorded already.
            return out;
        }
        if (node.isArray()) {
            out.put("items", String.valueOf(node.size()));
            return out;
        }
        if (!node.isObject()) return out;
        node.fields().forEachRemaining(f -> keep(out, f.getKey(), f.getValue()));
        claims(node.path("access_token").asText(""), out);
        return out;
    }

    /** One field of an answer: kept as is, counted (a key set), masked (a token or a secret) or left out. */
    private static void keep(Map<String, String> out, String k, JsonNode v) {
        if (k.equals("keys") && v.isArray()) out.put("keys", String.valueOf(v.size()));
        if (SHOWN_ANSWER.contains(k)) out.put(k, clip(v.isTextual() ? v.asText() : v.toString()));
        else if (TOKEN_FIELDS.contains(k)) out.put(k, MASK);
        else if (k.equals("value") || k.equals("secret")) out.put(k, MASK);
    }

    /** The kept claims of a JWT's payload; the token, its header and its signature are not kept. */
    private void claims(String jwt, Map<String, String> out) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return;
        try {
            JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            for (String c : SHOWN_CLAIMS) {
                if (payload.has(c)) {
                    JsonNode v = payload.get(c);
                    out.put("access_token." + c, clip(v.isTextual() ? v.asText() : v.toString()));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // Handled: a payload that does not decode shows no claim.
        }
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
