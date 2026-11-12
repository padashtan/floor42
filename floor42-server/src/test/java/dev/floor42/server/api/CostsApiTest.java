package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.MissionEvent.BudgetSet;
import dev.floor42.server.app.MissionGovernor;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.HookRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cost at a glance, with the fake Claude Code (which reports a cost and tokens): a real plan is approved only with its
 * cap decided, and the costs read from the journal match what the sessions reported, each figure with its basis.
 */
@QuarkusTest
class CostsApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    HookRegistry hooks;

    @Inject
    dev.floor42.server.journal.Journal journal;

    String mission(Path repo) {
        return api().body("{\"title\":\"Costed\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    void answerAllOpen(MissionId m) {
        office.query(c -> c.desk().of(m).stream()
                        .filter(q -> q.status() == QuestionStatus.OPEN)
                        .map(q -> q.id().value())
                        .toList())
                .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
    }

    @Test
    void aRealPlanWaitsForItsCapThenTheCostsAddUpWithTheirBases() throws Exception {
        String id = mission(LiveMissionTest.gitRepository());
        MissionId m = new MissionId(id);

        api().post("/api/missions/" + id + "/approve").then().statusCode(409).body("code", equalTo("CAP_NEEDED"));
        api().body("{\"budgetUsd\":3,\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(400);
        api().body("{\"budgetUsd\":-1}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(400);
        boolean decided = office.query(c -> c.missions().find(m).orElseThrow().capDecided());
        assertThat(decided).as("nothing recorded by refused approvals").isFalse();

        api().body("{\"budgetUsd\":3}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("budgetUsd", equalTo(3.0f))
                .body("capDecided", equalTo(true));
        assertThat(journal.readAll())
                .filteredOn(e -> e.event() instanceof BudgetSet b && b.mission().equals(m))
                .singleElement()
                .satisfies(e -> assertThat(e.actor().isDirector()).isTrue());

        await("the ticket done", Duration.ofSeconds(60), () -> {
            answerAllOpen(m);
            return office.query(c -> c.tickets().of(m).stream().allMatch(t -> t.status() == TicketStatus.DONE));
        });
        await(
                "every session closed",
                Duration.ofSeconds(20),
                () -> office.query(c -> c.sessions().of(m).stream().noneMatch(AgentSession::isOpen)));
        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        double reported = sessions.stream()
                .mapToDouble(s -> s.costUsdEstimated() == null ? 0 : s.costUsdEstimated())
                .sum();
        long in = sessions.stream()
                .mapToLong(s -> s.inputTokensProvided() == null ? 0 : s.inputTokensProvided())
                .sum();
        assertThat(reported).as("the fake reports a cost").isPositive();

        JsonPath detail = api().get("/api/missions/" + id + "/costs")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(detail.getDouble("mission.total.usdEstimated")).isCloseTo(reported, within());
        assertThat(detail.getLong("mission.total.inputTokens")).isEqualTo(in);
        assertThat(detail.getInt("mission.total.sessions")).isEqualTo(sessions.size());
        assertThat(detail.getString("mission.total.costBasis")).isEqualTo("estimated by Claude Code");
        assertThat(detail.getString("mission.total.tokensBasis")).isEqualTo("provided by Claude Code");
        assertThat(detail.getString("mission.total.sessionsBasis")).isEqualTo("measured by Floor42");
        assertThat(detail.getDouble("mission.budgetUsd")).isEqualTo(3.0);
        List<Map<String, Object>> agents = detail.getList("agents");
        assertThat(agents)
                .extracting(a -> a.get("key"))
                .containsExactlyInAnyOrderElementsOf(
                        sessions.stream().map(s -> s.agent().value()).distinct().toList());
        assertThat(agents.stream()
                        .mapToDouble(a -> ((Number) a.get("usdEstimated")).doubleValue())
                        .sum())
                .as("the agents add up to the mission")
                .isCloseTo(reported, within());
        List<Map<String, Object>> tickets = detail.getList("tickets");
        assertThat(tickets.stream()
                        .mapToInt(t -> ((Number) t.get("sessions")).intValue())
                        .sum())
                .isEqualTo(sessions.size());

        JsonPath overview = api().queryParam("zone", "Europe/Paris")
                .get("/api/costs")
                .then()
                .statusCode(200)
                .body("zone", equalTo("Europe/Paris"))
                .body("missions.mission", hasItem(id))
                .extract()
                .jsonPath();
        // The journal is shared with other test classes: today covers this mission's sessions at least.
        assertThat(overview.getDouble("today.usdEstimated")).isGreaterThanOrEqualTo(reported - 1e-9);
        assertThat(overview.getDouble("month.usdEstimated"))
                .isGreaterThanOrEqualTo(overview.getDouble("week.usdEstimated") - 1e-9);
        assertThat(overview.getString("today.costBasis")).isEqualTo("estimated by Claude Code");

        api().queryParam("zone", "Mars/Olympus").get("/api/costs").then().statusCode(400);
        api().get("/api/missions/M-99999/costs").then().statusCode(404);

        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    @Test
    void noCapIsAnExplicitChoiceOnTheRecord() throws Exception {
        String id = mission(LiveMissionTest.gitRepository());
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200)
                .body("budgetUsd", equalTo(null))
                .body("capDecided", equalTo(true));
        assertThat(journal.readAll())
                .filteredOn(e -> e.event() instanceof BudgetSet b && b.mission().equals(m))
                .singleElement()
                .satisfies(e -> assertThat(((BudgetSet) e.event()).usd()).isNull());
        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    @Test
    void anAgentSessionsCredentialCannotReadTheCosts() throws Exception {
        String id = mission(LiveMissionTest.gitRepository());
        MissionId m = new MissionId(id);
        var spec = new SessionSpec(
                m,
                office.query(c -> c.tickets().of(m).getFirst().id()),
                new AgentId("chris"),
                Origin.REAL,
                LiveMissionTest.gitRepository().toString(),
                "",
                "claude-sonnet-5-5",
                "medium",
                List.of("Read"));
        String credential = hooks.register(new MissionGovernor(office, spec));
        try {
            for (String path : List.of("/api/costs", "/api/missions/" + id + "/costs")) {
                given().header("Authorization", "Bearer " + credential)
                        .get(path)
                        .then()
                        .statusCode(403);
                api().get(path).then().statusCode(200);
            }
        } finally {
            hooks.unregister(credential);
            api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
        }
    }

    static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
