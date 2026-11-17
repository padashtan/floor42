package dev.floor42.server.iam.fga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.security.OwnerOnly;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The OpenFGA store and the one authorization model Floor42 checks against. At the start of IAM mode: find the store
 * named {@code floor42} or create it; compare the SHA-256 of the shipped {@code model.json} with the one recorded in
 * {@code <data-dir>/iam/fga.json}; when they differ (or the store is new) write the model, which is append-only in
 * OpenFGA, and record its id. Floor42 only ever checks against the recorded id. A recorded id the store does not know is
 * {@link FgaException#MODEL_MISMATCH}: sessions are refused and the status says why. Never a fallback to the files.
 */
@ApplicationScoped
public class FgaStore {

    private static final Logger LOG = Logger.getLogger(FgaStore.class);
    private static final String MODEL_RESOURCE = "/iam/openfga/model.json";

    /** Why OpenFGA cannot be used: a refusal code and the readable text. */
    public record Problem(String code, String reason) {}

    private record Ids(String storeId, String modelId, String modelSha256) {}

    private final FgaSettings settings;
    private final Path file;
    private final ObjectMapper json;
    private final FgaHttp http;
    private final Supplier<String> modelJson;
    private volatile Ids ready;

    @Inject
    FgaStore(@ConfigProperty(name = "floor42.data-dir") Path dataDir, FgaSettings settings, ObjectMapper json) {
        this(dataDir.resolve("iam").resolve("fga.json"), settings, json, FgaStore::shippedModel);
    }

    FgaStore(Path file, FgaSettings settings, ObjectMapper json, Supplier<String> modelJson) {
        this.file = file;
        this.settings = settings;
        this.json = json;
        this.http = new FgaHttp(
                () -> settings.url().orElseThrow(() -> new FgaException(FgaException.NEEDED, "No OpenFGA url is set")),
                settings::key,
                json);
        this.modelJson = modelJson;
    }

    /**
     * Makes the store and the model ready, or says why not. Empty when OpenFGA is usable, and also when it is not
     * required and not configured (then {@link #client()} refuses).
     */
    public synchronized Optional<Problem> ensureReady() {
        ready = null;
        if (settings.url().isEmpty()) {
            return settings.required()
                    ? Optional.of(new Problem(
                            FgaException.NEEDED,
                            "IAM mode needs OpenFGA: set floor42.iam.fga.url and its key (iam/README.md)"))
                    : Optional.empty();
        }
        try {
            String store = findOrCreateStore();
            String model = modelJson.get();
            String sha = sha256(model);
            Ids recorded = read().filter(i -> i.storeId().equals(store)).orElse(null);
            if (recorded == null || !recorded.modelSha256().equals(sha)) {
                String id = writeModel(store, model);
                recorded = new Ids(store, id, sha);
                save(recorded);
                LOG.info("OpenFGA model written, id " + id);
            } else if (!modelKnown(store, recorded.modelId())) {
                throw new FgaException(
                        FgaException.MODEL_MISMATCH,
                        "OpenFGA does not know the model " + recorded.modelId() + " recorded in " + file.getFileName());
            }
            ready = recorded;
            return Optional.empty();
        } catch (FgaException e) { // Handled: returned to the caller, which fails closed.
            return Optional.of(new Problem(e.code(), e.getMessage()));
        }
    }

    /** The client for the recorded store and model; refuses while {@link #ensureReady} has not succeeded. */
    public FgaClient client() {
        Ids i = ready;
        if (i == null) throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA is not ready");
        return new HttpFgaClient(http, i.storeId(), i.modelId());
    }

    /** The recorded model id, for the decision basis ({@code fga:<id>}); empty until ready. */
    public Optional<String> modelId() {
        return Optional.ofNullable(ready).map(Ids::modelId);
    }

    private String findOrCreateStore() {
        FgaHttp.Reply list = http.call("GET", "/stores?page_size=100&name=" + FgaSettings.STORE_NAME, null);
        if (!list.ok() || list.body() == null || !list.body().path("stores").isArray())
            throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA answered the store list without stores");
        for (JsonNode s : list.body().get("stores"))
            if (FgaSettings.STORE_NAME.equals(s.path("name").asText())
                    && !s.path("id").asText().isEmpty()) return s.get("id").asText();
        ObjectNode body = json.createObjectNode().put("name", FgaSettings.STORE_NAME);
        FgaHttp.Reply made = http.call("POST", "/stores", body);
        String id = made.ok() && made.body() != null ? made.body().path("id").asText("") : "";
        if (id.isEmpty()) throw new FgaException(FgaException.UNAVAILABLE, "OpenFGA did not create the store");
        return id;
    }

    private String writeModel(String store, String model) {
        JsonNode body;
        try {
            body = json.readTree(model);
        } catch (IOException e) {
            throw new FgaException(FgaException.MODEL_MISMATCH, "The shipped model is not JSON", e);
        }
        FgaHttp.Reply r = http.call("POST", "/stores/" + store + "/authorization-models", body);
        String id = r.ok() && r.body() != null
                ? r.body().path("authorization_model_id").asText("")
                : "";
        if (id.isEmpty())
            throw new FgaException(FgaException.MODEL_MISMATCH, "OpenFGA refused the model (HTTP " + r.status() + ")");
        return id;
    }

    private boolean modelKnown(String store, String modelId) {
        FgaHttp.Reply r = http.call("GET", "/stores/" + store + "/authorization-models/" + modelId, null);
        return r.ok();
    }

    private Optional<Ids> read() {
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode n = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            Ids i = new Ids(
                    n.path("storeId").asText(""),
                    n.path("modelId").asText(""),
                    n.path("modelSha256").asText(""));
            return i.storeId().isEmpty() || i.modelId().isEmpty() ? Optional.empty() : Optional.of(i);
        } catch (IOException | RuntimeException e) {
            // Handled: a damaged file only means the model is written again (models are append-only in OpenFGA)
            LOG.warn("fga.json cannot be read, the model is written again: "
                    + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void save(Ids i) {
        try {
            Files.createDirectories(file.getParent());
            OwnerOnly.restrict(file.getParent());
            Path tmp = file.resolveSibling("fga.json.tmp");
            ObjectNode n = json.createObjectNode();
            n.put("storeId", i.storeId()).put("modelId", i.modelId()).put("modelSha256", i.modelSha256());
            Files.write(tmp, json.writeValueAsBytes(n));
            OwnerOnly.restrict(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save fga.json", e);
        }
    }

    private static String shippedModel() {
        try (InputStream in = FgaStore.class.getResourceAsStream(MODEL_RESOURCE)) {
            if (in == null) throw new FgaException(FgaException.MODEL_MISMATCH, "The shipped model is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FgaException(FgaException.MODEL_MISMATCH, "The shipped model cannot be read", e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
