package dev.floor42.server.api.gateway;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.floor42.domain.ActionCategory;
import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Decision;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.api.LiveTestSupport;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.session.MissionGovernor;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The director's MCP endpoint answers an agent's own {@code ask_director} question and the live fake session gets the
 * answer (ADR-0073). Its own daemon: the session is driven by hand, as {@code ClaudeCodeRuntimeTest} does.
 */
@QuarkusTest
@TestProfile(DirectorMcpAskTest.Own.class)
class DirectorMcpAskTest extends ApiTestBase {

    public static class Own implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of(
                    "floor42.live-missions.enabled", "false", "floor42.sandbox.assume-available", "true");
        }
    }

    static final AgentId CHRIS = new AgentId("chris");

    @Inject
    OfficeService office;

    @Inject
    ClaudeCodeRuntime runtime;

    @Inject
    Journal journal;

    /** Built on the test thread: its data folder cannot be resolved from a worker thread. */
    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    dev.floor42.domain.CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    JsonNode tool(String bearer, String name, String arguments) {
        return given().contentType("application/json")
                .header("Authorization", "Bearer " + bearer)
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                        + "\",\"arguments\":" + arguments + "}}")
                .post("/api/mcp")
                .then()
                .statusCode(200)
                .extract()
                .as(JsonNode.class)
                .path("result");
    }

    QuestionStatus status(String id) {
        return office.query(c -> c.desk()
                .find(new dev.floor42.domain.QuestionId(id))
                .orElseThrow()
                .status());
    }

    /** A real mission with one started ticket of chris, as ClaudeCodeRuntimeTest builds it. */
    SessionSpec started(String brief) throws Exception {
        Path workdir = Files.createTempDirectory("office-session");
        if (office.query(c -> c.staff().find(CHRIS).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            director(),
                            CHRIS,
                            "Chris",
                            "Java developer",
                            "anthropic",
                            "claude-sonnet-5-5",
                            "medium",
                            List.of()));
        }
        MissionId m = ((MissionCreated) office.execute(c -> c.missions()
                                .create(
                                        director(),
                                        "Inspect",
                                        "Inspect the repository",
                                        workdir.toString(),
                                        Workflow.BUILT_IN))
                        .getFirst()
                        .event())
                .mission();
        TicketId t = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(director(), m, "Inspect", "", Priority.NORMAL, List.of(), List.of("report")))
                        .getFirst()
                        .event())
                .ticket();
        office.execute(c -> c.tickets().assign(director(), t, CHRIS));
        office.execute(c -> c.missions().setToolPermission(director(), m, "Read", ActionCategory.READ, Decision.ALLOW));
        office.execute(c -> c.missions().submitPlan(director(), m));
        office.execute(c -> c.verification().setBudget(director(), m, null));
        office.execute(c -> c.missions().approvePlan(director(), m));
        office.execute(c -> c.tickets().start(office.context(Actor.agent(CHRIS), Origin.REAL), t));
        return new SessionSpec(
                m, t, CHRIS, Origin.REAL, workdir.toString(), brief, "claude-sonnet-5-5", "medium", List.of("Read"));
    }

    /**
     * A fake session asks through ask_director (options blue and red): a wrong option comes back as a tool error
     * inside the envelope, a right one is answered, and the session reads the answer.
     */
    @Test
    void anAgentsAskDirectorIsAnsweredAndTheSessionGetsTheAnswer() throws Exception {
        SessionSpec spec = started("[fake:ask=Which colour for the banner?]");
        String write = api().body("{\"scope\":\"missions:write\",\"days\":1}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        sandbox.enabled();
        CompletableFuture<?> run =
                CompletableFuture.supplyAsync(() -> runtime.run(spec, new MissionGovernor(office, spec)));
        Question[] asked = new Question[1];
        LiveTestSupport.await("the agent's question", Duration.ofSeconds(30), () -> {
            asked[0] = office.query(c -> c.desk().of(spec.mission()).stream()
                    .filter(q -> q.status() == QuestionStatus.OPEN)
                    .findFirst()
                    .orElse(null));
            return asked[0] != null;
        });
        String id = asked[0].id().value();
        JsonNode wrong = tool(write, "answer_question", "{\"id\":\"" + id + "\",\"answer\":\"green\"}");
        assertThat(wrong.path("isError").asBoolean()).isTrue();
        assertThat(wrong.path("content").path(0).path("text").asText())
                .startsWith("UNTRUSTED DATA")
                .contains("INVALID_ANSWER");
        assertThat(status(id)).isEqualTo(QuestionStatus.OPEN);
        JsonNode ok = tool(write, "answer_question", "{\"id\":\"" + id + "\",\"answer\":\"blue\"}");
        assertThat(ok.path("isError").asBoolean()).as(ok.toString()).isFalse();
        assertThat(status(id)).isEqualTo(QuestionStatus.ANSWERED);
        run.get(60, TimeUnit.SECONDS);
        assertThat(journal.readAll()).anyMatch(e -> e.toString().contains("The director answered: blue"));
    }
}
