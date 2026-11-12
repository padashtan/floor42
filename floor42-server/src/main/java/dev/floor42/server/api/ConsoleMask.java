package dev.floor42.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the API console may keep of a body: never a secret, password or token value. JSON is read as JSON, so an
 * escaped key or a value of any type (number, array, object) is masked like a string; a body that cannot be read and
 * may hold a secret is not kept at all. Routes that carry a secret by design keep no body ({@link #secretRoute}).
 */
final class ConsoleMask {

    static final String MASK = "***";
    static final String WITHHELD = "[not kept: it may hold a secret]";

    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)secret|password|passwd|token|credential|api.?key|authorization");
    /** A form body (client_secret=...&password=...): only for bodies that are not JSON. */
    private static final Pattern FORM =
            Pattern.compile("(?i)((?:^|&)[^=&]*(?:secret|password|passwd|token|credential|code)[^=&]*=)[^&]*");

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ConsoleMask() {}

    /** Routes whose request carries a secret by design: the console keeps none of their bodies. */
    static boolean secretRoute(String path) {
        return path.startsWith("/api/iam/secrets/") || path.equals("/api/iam/settings");
    }

    static String masked(String body) {
        String t = body.strip();
        if (t.isEmpty()) return body;
        if (t.charAt(0) != '{' && t.charAt(0) != '[') return FORM.matcher(body).replaceAll("$1" + MASK);
        JsonNode tree;
        try {
            tree = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            // Handled: cut by the body limit, or not JSON after all; an escaped key could hide a secret name, so a body
            // that may hold one is not kept.
            return SECRET_KEY.matcher(body).find() || body.contains("\\u") ? WITHHELD : body;
        }
        if (!mask(tree)) return body;
        try {
            return JSON.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            // Handled: a tree just read always writes back; if not, nothing is kept rather than the raw body.
            return WITHHELD;
        }
    }

    /** Masks in place; true when something was masked. */
    private static boolean mask(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode o) {
            List<String> names = new ArrayList<>();
            o.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (SECRET_KEY.matcher(name).find()) {
                    o.set(name, TextNode.valueOf(MASK));
                    changed = true;
                } else changed |= mask(o.get(name));
            }
        } else if (node instanceof ArrayNode a) {
            for (JsonNode item : a) changed |= mask(item);
        }
        return changed;
    }
}
