package dev.floor42.server.iam.fga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * {@link FgaClient} on the JDK HTTP client. Every check pins the authorization model and asks
 * {@code HIGHER_CONSISTENCY} (measured 2026-11-16: with OpenFGA's caches on, only that value reads a revoke at once; an
 * unknown value is silently accepted, so a test proves the field is sent). No cache here.
 */
public final class HttpFgaClient implements FgaClient {

    private static final Logger LOG = Logger.getLogger(HttpFgaClient.class);
    private static final int MAX_PAGES = 20;

    private final FgaHttp http;
    private final String store;
    private final String model;
    private final AtomicBoolean mismatchLogged = new AtomicBoolean();

    HttpFgaClient(FgaHttp http, String store, String model) {
        this.http = http;
        this.store = store;
        this.model = model;
    }

    @Override
    public boolean check(String user, String relation, String object, List<FgaTuple> contextual) {
        ObjectNode body = http.json().createObjectNode();
        key(body.putObject("tuple_key"), new FgaTuple(user, relation, object));
        if (!contextual.isEmpty()) keys(body.putObject("contextual_tuples").putArray("tuple_keys"), contextual);
        body.put("authorization_model_id", model);
        body.put("consistency", "HIGHER_CONSISTENCY");
        FgaHttp.Reply r = http.call("POST", "/stores/" + store + "/check", body);
        if (r.status() == 400 || r.status() == 404) {
            if (mismatchLogged.compareAndSet(false, true))
                LOG.warn("OpenFGA refused a check as invalid for the pinned model (HTTP " + r.status() + ")");
            throw new FgaException(
                    FgaException.MODEL_MISMATCH,
                    "OpenFGA does not know the pinned model or store (HTTP " + r.status() + ")");
        }
        JsonNode allowed = r.body() == null ? null : r.body().get("allowed");
        if (allowed == null || !allowed.isBoolean())
            throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA answered a check without a boolean");
        return allowed.asBoolean();
    }

    @Override
    public void write(List<FgaTuple> writes, List<FgaTuple> deletes) {
        FgaClient.refuseUnstorable(writes, deletes);
        if (writes.isEmpty() && deletes.isEmpty()) return;
        ObjectNode body = http.json().createObjectNode();
        body.put("authorization_model_id", model);
        if (!writes.isEmpty()) {
            ObjectNode w = body.putObject("writes");
            keys(w.putArray("tuple_keys"), writes);
            w.put("on_duplicate", "ignore");
        }
        if (!deletes.isEmpty()) {
            ObjectNode d = body.putObject("deletes");
            keys(d.putArray("tuple_keys"), deletes);
            d.put("on_missing", "ignore");
        }
        FgaHttp.Reply r = http.call("POST", "/stores/" + store + "/write", body);
        if (r.status() == 400 || r.status() == 404)
            throw new FgaException(
                    FgaException.INVALID, "OpenFGA refused the write as invalid (HTTP " + r.status() + ")");
    }

    @Override
    public List<FgaTuple> read(String object) {
        List<FgaTuple> out = new ArrayList<>();
        String token = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode body = http.json().createObjectNode();
            body.putObject("tuple_key").put("object", object);
            body.put("page_size", 100);
            if (!token.isEmpty()) body.put("continuation_token", token);
            FgaHttp.Reply r = http.call("POST", "/stores/" + store + "/read", body);
            if (!r.ok() || r.body() == null || !r.body().path("tuples").isArray())
                throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA answered a read without tuples");
            for (JsonNode t : r.body().get("tuples")) {
                JsonNode k = t.path("key");
                out.add(new FgaTuple(
                        k.path("user").asText(),
                        k.path("relation").asText(),
                        k.path("object").asText()));
            }
            token = r.body().path("continuation_token").asText("");
            if (token.isEmpty()) return out;
        }
        throw new FgaException(FgaException.UNAVAILABLE, "A read of " + object + " ran past " + MAX_PAGES + " pages");
    }

    private static void key(ObjectNode n, FgaTuple t) {
        n.put("user", t.user()).put("relation", t.relation()).put("object", t.object());
    }

    private static void keys(ArrayNode a, List<FgaTuple> tuples) {
        tuples.forEach(t -> key(a.addObject(), t));
    }
}
