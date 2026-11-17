package dev.floor42.server.iam.fga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.server.iam.fga.FgaStub.Req;
import dev.floor42.server.iam.fga.FgaStub.Res;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The store and the pinned model: written once per model, recorded, and refused when the store disowns the id. */
class FgaStoreTest {

    private static final String MODEL_A = "{\"schema_version\":\"1.1\",\"type_definitions\":[{\"type\":\"user\"}]}";
    private static final String MODEL_B =
            "{\"schema_version\":\"1.1\",\"type_definitions\":[{\"type\":\"user\"},{\"type\":\"agent\"}]}";

    @TempDir
    Path dir;

    FgaStub stub;
    final Set<String> knownModels = new HashSet<>();
    final AtomicInteger modelsWritten = new AtomicInteger();
    final AtomicInteger storesCreated = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        stub = new FgaStub();
        stub.handler = this::answer;
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    /** A tiny OpenFGA: one store once created, models numbered m1, m2... */
    private Res answer(Req r) {
        String p = r.path();
        if (r.method().equals("GET") && p.startsWith("/stores?"))
            return new Res(
                    200,
                    storesCreated.get() == 0
                            ? "{\"stores\":[]}"
                            : "{\"stores\":[{\"id\":\"s1\",\"name\":\"floor42\"}]}");
        if (r.method().equals("POST") && p.equals("/stores")) {
            storesCreated.incrementAndGet();
            return new Res(201, "{\"id\":\"s1\",\"name\":\"floor42\"}");
        }
        if (r.method().equals("POST") && p.equals("/stores/s1/authorization-models")) {
            String id = "m" + modelsWritten.incrementAndGet();
            knownModels.add(id);
            return new Res(201, "{\"authorization_model_id\":\"" + id + "\"}");
        }
        if (r.method().equals("GET") && p.startsWith("/stores/s1/authorization-models/"))
            return knownModels.contains(p.substring(p.lastIndexOf('/') + 1))
                    ? new Res(200, "{\"authorization_model\":{}}")
                    : new Res(404, "{\"code\":\"authorization_model_not_found\"}");
        return new Res(404, "{}");
    }

    private FgaStore store(String model, Optional<String> url, boolean required) {
        var settings = new FgaSettings(url.map(String::valueOf), Optional.of("k"), required, null);
        return new FgaStore(dir.resolve("iam").resolve("fga.json"), settings, FgaStub.JSON, () -> model);
    }

    private FgaStore store(String model) {
        return store(model, Optional.of(stub.url().toString()), true);
    }

    @Test
    void aChangedModelIsWrittenOnceAndItsIdRecorded() throws IOException {
        assertThat(store(MODEL_A).ensureReady()).isEmpty();
        assertThat(modelsWritten.get()).isEqualTo(1);
        assertThat(storesCreated.get()).isEqualTo(1);

        // Same model, a new start: nothing is written, the recorded id is verified
        FgaStore again = store(MODEL_A);
        assertThat(again.ensureReady()).isEmpty();
        assertThat(modelsWritten.get()).isEqualTo(1);
        assertThat(again.modelId()).contains("m1");
        assertThat(storesCreated.get()).as("the store is found by name").isEqualTo(1);

        // Another model: written once more, the new id recorded
        FgaStore changed = store(MODEL_B);
        assertThat(changed.ensureReady()).isEmpty();
        assertThat(modelsWritten.get()).isEqualTo(2);
        assertThat(changed.modelId()).contains("m2");
        JsonNode file =
                FgaStub.JSON.readTree(Files.readString(dir.resolve("iam").resolve("fga.json")));
        assertThat(file.get("modelId").asText()).isEqualTo("m2");
        assertThat(file.get("storeId").asText()).isEqualTo("s1");
        assertThat(file.get("modelSha256").asText()).isEqualTo(FgaStore.sha256(MODEL_B));
        assertThat(store(MODEL_B).ensureReady()).isEmpty();
        assertThat(modelsWritten.get()).isEqualTo(2);
    }

    @Test
    void anUnknownModelIdRefusesSessions() {
        assertThat(store(MODEL_A).ensureReady()).isEmpty();
        knownModels.clear(); // the store lost the model the file records
        FgaStore s = store(MODEL_A);
        Optional<FgaStore.Problem> problem = s.ensureReady();
        assertThat(problem).isPresent();
        assertThat(problem.get().code()).isEqualTo(FgaException.MODEL_MISMATCH);
        Throwable t = catchThrowable(s::client);
        assertThat(t).isInstanceOf(FgaException.class);
        assertThat(((FgaException) t).code()).isEqualTo(FgaException.UNAVAILABLE);
        assertThat(modelsWritten.get())
                .as("no silent rewrite of a model Floor42 recorded")
                .isEqualTo(1);
    }

    @Test
    void withoutAnUrlIamNeedsFga() {
        var problem = store(MODEL_A, Optional.empty(), true).ensureReady();
        assertThat(problem).isPresent();
        assertThat(problem.get().code()).isEqualTo(FgaException.NEEDED);
        assertThat(stub.requests).isEmpty();
        assertThat(store(MODEL_A, Optional.empty(), false).ensureReady()).isEmpty();
    }

    @Test
    void anUnreachableOpenFgaIsUnavailable() {
        String url = stub.url().toString();
        stub.close();
        var problem = store(MODEL_A, Optional.of(url), true).ensureReady();
        assertThat(problem).isPresent();
        assertThat(problem.get().code()).isEqualTo(FgaException.UNAVAILABLE);
    }

    @Test
    void theClientIsPinnedToTheRecordedStoreAndModel() {
        FgaStore s = store(MODEL_A);
        assertThat(s.ensureReady()).isEmpty();
        stub.requests.clear();
        stub.handler = r -> new Res(200, "{\"allowed\":true}");
        assertThat(s.client().check("agent:ada", "can_call", "resource:jira", java.util.List.of()))
                .isTrue();
        assertThat(stub.requests.get(0).path()).isEqualTo("/stores/s1/check");
        assertThat(stub.requests.get(0).body().get("authorization_model_id").asText())
                .isEqualTo("m1");
    }
}
