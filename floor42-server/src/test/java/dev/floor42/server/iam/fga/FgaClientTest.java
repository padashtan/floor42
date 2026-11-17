package dev.floor42.server.iam.fga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.floor42.server.iam.fga.FgaStub.Res;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP client against a stub of OpenFGA: what it sends, and that anything but a clear yes is a refusal. */
class FgaClientTest {

    private static final String KEY = "preshared-key-0123456789abcdef";

    FgaStub stub;
    HttpFgaClient client;

    @BeforeEach
    void start() throws IOException {
        stub = new FgaStub();
        client = new HttpFgaClient(new FgaHttp(stub::url, () -> Optional.of(KEY), FgaStub.JSON), "store-1", "model-1");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private FgaException refused(Runnable call) {
        Throwable t = catchThrowable(call::run);
        assertThat(t).as("the call must be refused").isInstanceOf(FgaException.class);
        return (FgaException) t;
    }

    @Test
    void aNon200IsARefusalNeverAnAllow() {
        stub.handler = r -> new Res(500, "{\"allowed\":true}");
        FgaException e = refused(() -> client.check("agent:ada", "can_call", "resource:jira", List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.UNAVAILABLE);
    }

    @Test
    void aTimeoutIsARefusal() {
        stub.delayMillis = 3000;
        stub.handler = r -> new Res(200, "{\"allowed\":true}");
        FgaException e = refused(() -> client.check("agent:ada", "can_call", "resource:jira", List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.UNAVAILABLE);
    }

    @Test
    void anUnreachableServerIsARefusal() {
        stub.close();
        FgaException e = refused(() -> client.check("agent:ada", "can_call", "resource:jira", List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.UNAVAILABLE);
    }

    @Test
    void aMalformedAnswerIsARefusal() {
        for (String body : new String[] {"{}", "{\"allowed\":\"yes\"}", "{\"allowed\":null}", "not json", ""}) {
            stub.handler = r -> new Res(200, body);
            FgaException e = refused(() -> client.check("agent:ada", "can_call", "resource:jira", List.of()));
            assertThat(e).as("answer [%s]", body).isNotNull();
            assertThat(e.code()).isEqualTo(FgaException.UNAVAILABLE);
        }
    }

    @Test
    void a400OnACheckIsAModelMismatch() {
        stub.handler = r -> new Res(400, "{\"code\":\"validation_error\"}");
        FgaException e = refused(() -> client.check("agent:ada", "can_call", "resource:jira", List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.MODEL_MISMATCH);
    }

    @Test
    void everyCheckPinsTheModelAndAsksHigherConsistency() {
        stub.handler = r -> new Res(200, "{\"allowed\":true}");
        var runner = new FgaTuple("agent:ada", "runner", "mission:m1");
        assertThat(client.check("agent:ada", "can_call", "resource:jira", List.of(runner)))
                .isTrue();
        var req = stub.requests.get(0);
        assertThat(req.path()).isEqualTo("/stores/store-1/check");
        assertThat(req.body().get("authorization_model_id").asText()).isEqualTo("model-1");
        // The server accepts any value silently (measured), so only the request body can prove the field is sent
        assertThat(req.body().get("consistency").asText()).isEqualTo("HIGHER_CONSISTENCY");
        assertThat(req.body().at("/tuple_key/user").asText()).isEqualTo("agent:ada");
        assertThat(req.body().at("/tuple_key/relation").asText()).isEqualTo("can_call");
        assertThat(req.body().at("/tuple_key/object").asText()).isEqualTo("resource:jira");
        assertThat(req.body().at("/contextual_tuples/tuple_keys/0/relation").asText())
                .isEqualTo("runner");
    }

    @Test
    void aFalseAnswerIsARefusalToo() {
        stub.handler = r -> new Res(200, "{\"allowed\":false}");
        assertThat(client.check("agent:ada", "can_call", "resource:jira", List.of()))
                .isFalse();
    }

    @Test
    void theKeyIsSentAndNeverLogged() {
        List<String> logged = new ArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.addHandler(h);
        try {
            stub.handler = r -> new Res(200, "{\"allowed\":true}");
            client.check("agent:ada", "can_call", "resource:jira", List.of());
            assertThat(stub.requests.get(0).authorization()).isEqualTo("Bearer " + KEY);

            stub.handler = r -> new Res(401, "{\"code\":\"unauthenticated\"}");
            FgaException e = refused(() -> client.check("agent:ada", "can_call", "resource:jira", List.of()));
            assertThat(e.code()).isEqualTo(FgaException.UNAVAILABLE);
            assertThat(e.getMessage()).containsIgnoringCase("refused Floor42").doesNotContain(KEY);
        } finally {
            root.removeHandler(h);
        }
        assertThat(String.join("\n", logged)).doesNotContain(KEY);
    }

    @Test
    void aRunnerTupleIsNeverWritten() {
        var runner = new FgaTuple("agent:ada", "runner", "mission:m1");
        FgaException e = refused(() -> client.write(List.of(runner), List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.INVALID);
        var member = new FgaTuple("user:rev", "member", "role:floor42-reviewer");
        assertThat(refused(() -> client.write(List.of(member), List.of()))).isNotNull();
        assertThat(stub.requests).as("nothing left the process").isEmpty();
    }

    @Test
    void moreThanOneHundredTuplesAreRefused() {
        List<FgaTuple> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(new FgaTuple("agent:a" + i, "granted", "resource:r"));
        FgaException e = refused(() -> client.write(many, List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.INVALID);
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void aWriteIsOneIdempotentCallWithNoReadBeforeIt() {
        stub.handler = r -> new Res(200, "{}");
        var g = new FgaTuple("agent:ada", "granted", "resource:jira");
        var old = new FgaTuple("agent:bob", "granted", "resource:jira");
        client.write(List.of(g), List.of(old));
        assertThat(stub.requests).hasSize(1);
        var req = stub.requests.get(0);
        assertThat(req.path()).isEqualTo("/stores/store-1/write");
        assertThat(req.body().get("authorization_model_id").asText()).isEqualTo("model-1");
        assertThat(req.body().at("/writes/on_duplicate").asText()).isEqualTo("ignore");
        assertThat(req.body().at("/deletes/on_missing").asText()).isEqualTo("ignore");
        assertThat(req.body().at("/writes/tuple_keys/0/object").asText()).isEqualTo("resource:jira");
        assertThat(req.body().at("/deletes/tuple_keys/0/user").asText()).isEqualTo("agent:bob");
    }

    @Test
    void aWriteOpenFgaRefusesIsInvalidNotUnavailable() {
        stub.handler = r -> new Res(400, "{\"code\":\"write_failed_due_to_invalid_input\"}");
        FgaException e =
                refused(() -> client.write(List.of(new FgaTuple("agent:ada", "granted", "resource:jira")), List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(FgaException.INVALID);
    }

    @Test
    void aReadFollowsTheContinuationToken() {
        stub.handler = r -> r.body().has("continuation_token")
                ? new Res(
                        200,
                        "{\"tuples\":[{\"key\":{\"user\":\"agent:b\",\"relation\":\"granted\",\"object\":\"resource:jira\"}}],"
                                + "\"continuation_token\":\"\"}")
                : new Res(
                        200,
                        "{\"tuples\":[{\"key\":{\"user\":\"agent:a\",\"relation\":\"granted\",\"object\":\"resource:jira\"}}],"
                                + "\"continuation_token\":\"next\"}");
        assertThat(client.read("resource:jira")).extracting(FgaTuple::user).containsExactly("agent:a", "agent:b");
        assertThat(stub.requests.get(0).body().at("/tuple_key/object").asText()).isEqualTo("resource:jira");
    }
}
