package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.RosterEvent;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.MissionScheduler;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SchedulerQuiet;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.runtime.AgentRuntime;
import dev.floor42.server.runtime.HookRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The director stars core members, then dismisses everyone else: a preview first, then each confirmed agent through
 * the single dismissal's path. The journal is shared with every other test: only agents hired here are ever let go.
 */
@QuarkusTest
class CoreMembersApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    @Inject
    dev.floor42.server.app.StaffProvisioner staff;

    @Inject
    Journal journal;

    @Inject
    HookRegistry hooks;

    @Inject
    MissionScheduler scheduler;

    String hire(String name) {
        return api().body("{\"name\":\"" + name + "\",\"workRole\":\"RESEARCHER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    Map<String, Object> entry(JsonPath preview, String id) {
        return preview.getMap("agents.find { it.id == '" + id + "' }");
    }

    /** The star events recorded for one agent. */
    long starsOf(String id) {
        return journal.readAll().stream()
                .map(EventEnvelope::event)
                .filter(e -> e.equals(new RosterEvent.AgentMarkedCore(new AgentId(id)))
                        || e.equals(new RosterEvent.AgentUnmarkedCore(new AgentId(id))))
                .count();
    }

    @Test
    void aStarIsJournaledAsTheDirectorsRealDecisionAndShownOnTheAgent() {
        String id = hire("Stella");
        api().body("{\"core\":true}")
                .put("/api/agents/" + id + "/core")
                .then()
                .statusCode(200)
                .body("core", equalTo(true));
        api().get("/api/agents").then().body("find { it.id == '" + id + "' }.core", equalTo(true));
        // Again: nothing more is recorded for this agent (other tests share the journal, so only its own events count).
        long before = starsOf(id);
        api().body("{\"core\":true}").put("/api/agents/" + id + "/core").then().statusCode(200);
        assertThat(starsOf(id)).isEqualTo(before);

        EventEnvelope marked = journal.readAll().stream()
                .filter(e -> e.event().equals(new RosterEvent.AgentMarkedCore(new AgentId(id))))
                .findFirst()
                .orElseThrow();
        assertThat(marked.origin()).isEqualTo(Origin.REAL);
        assertThat(marked.actor()).isEqualTo(Actor.director());

        api().body("{\"core\":false}")
                .put("/api/agents/" + id + "/core")
                .then()
                .statusCode(200)
                .body("core", equalTo(false));
        assertThat(journal.readAll().stream()
                        .map(EventEnvelope::event)
                        .filter(e ->
                                e instanceof RosterEvent.AgentMarkedCore || e instanceof RosterEvent.AgentUnmarkedCore)
                        .filter(e -> e.toString().contains(id))
                        .toList()
                        .getLast())
                .isEqualTo(new RosterEvent.AgentUnmarkedCore(new AgentId(id)));
        api().body("{}").put("/api/agents/" + id + "/core").then().statusCode(400);
        api().body("{\"reason\":\"test done\"}")
                .post("/api/agents/" + id + "/dismiss")
                .then()
                .statusCode(200);
    }

    @Test
    void everyoneButTheCoreLeavesOneByOneAndNobodyAtWorkIsTouched() {
        staff.ensurePlanner();
        String keep = hire("Kora");
        String go = hire("Gaston");
        String busy = hire("Bettina");
        api().body("{\"core\":true}")
                .put("/api/agents/" + keep + "/core")
                .then()
                .statusCode(200);
        // A task the dispatcher chose for Bettina keeps her, as it would refuse her single dismissal.
        var reservation = dev.floor42.server.app.LiveSeams.reserve(live, new AgentId(busy));
        try {
            JsonPath preview = api().get("/api/agents/dismiss-all-but-core")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            assertThat(entry(preview, keep)).containsEntry("fate", "STAYS");
            assertThat(entry(preview, go)).containsEntry("fate", "LEAVES").containsEntry("code", null);
            assertThat(entry(preview, busy)).containsEntry("fate", "BLOCKED").containsEntry("code", "AGENT_BUSY");
            assertThat(entry(preview, "galen"))
                    .as("a chair, as the single dismissal says")
                    .containsEntry("fate", "BLOCKED")
                    .containsEntry("code", "AGENT_NEEDED");
            List<String> named = List.of(go, busy, keep, "galen");
            assertThat(dismissalsOf(named)).as("none of them has left yet").isEmpty();

            // The confirmation names Kora and Galen too: a core member is skipped, a chair refused.
            JsonPath report = api().body("{\"agents\":[\"" + go + "\",\"" + busy + "\",\"" + keep + "\",\"galen\"],"
                            + "\"reason\":\"core team only\"}")
                    .post("/api/agents/dismiss-all-but-core")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath();
            assertThat(report.getList("outcomes.id")).containsExactly(go, busy, keep, "galen");
            assertThat(report.getMap("outcomes[0]")).containsEntry("result", "DISMISSED");
            assertThat(report.getMap("outcomes[1]"))
                    .containsEntry("result", "REFUSED")
                    .containsEntry("code", "AGENT_BUSY");
            assertThat(report.getMap("outcomes[2]"))
                    .containsEntry("result", "SKIPPED")
                    .containsEntry("code", "CORE");
            assertThat(report.getMap("outcomes[3]"))
                    .containsEntry("result", "REFUSED")
                    .containsEntry("code", "AGENT_NEEDED");

            api().get("/api/agents")
                    .then()
                    .body("id", not(hasItem(go)))
                    .body("id", hasItem(busy))
                    .body("id", hasItem(keep))
                    .body("id", hasItem("galen"));
            // One AgentDismissed per agent who left, as a single dismissal records it: counted for the agents named
            // here only, since other tests dismiss theirs in the same journal.
            List<EventEnvelope> dismissed = dismissalsOf(named);
            assertThat(dismissed).hasSize(1);
            EventEnvelope left = dismissed.getFirst();
            assertThat(((RosterEvent.AgentDismissed) left.event()).agent().value())
                    .isEqualTo(go);
            assertThat(left.origin()).isEqualTo(Origin.REAL);
            assertThat(left.actor()).isEqualTo(Actor.director());
            assertThat(((RosterEvent.AgentDismissed) left.event()).reason()).isEqualTo("core team only");
        } finally {
            live.release(reservation);
            // Leave no extra researcher behind for the other tests.
            api().body("{\"core\":false}").put("/api/agents/" + keep + "/core");
            api().body("{\"agents\":[\"" + keep + "\",\"" + busy + "\"]}").post("/api/agents/dismiss-all-but-core");
        }
        api().body("{}").post("/api/agents/dismiss-all-but-core").then().statusCode(400);
    }

    /** The AgentDismissed events recorded for these agents. */
    List<EventEnvelope> dismissalsOf(List<String> agents) {
        return journal.readAll().stream()
                .filter(e -> e.event() instanceof RosterEvent.AgentDismissed d
                        && agents.contains(d.agent().value()))
                .toList();
    }

    @Test
    void anAgentWhoseSessionIsOpenIsBlockedAndRefusedNeverDismissed() throws Exception {
        // Whatever ran before, no agent is still at work: the one this mission gets is free.
        SchedulerQuiet.await(scheduler);
        // A real mission on the fake Claude Code: its developer's session stays open for a while.
        String repo = LiveMissionTest.gitRepository().toString().replace("\\", "\\\\");
        MissionId m = new MissionId(api().body("{\"title\":\"Core\",\"objective\":\"Write notes [fake:slow=10]\","
                        + "\"repository\":\"" + repo + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        api().contentType("application/json")
                .body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await("a session runs", Duration.ofSeconds(30), () -> {
            allow(m);
            return office.query(c -> c.sessions().of(m).stream().anyMatch(AgentSession::isOpen));
        });
        AgentId worker = office.query(c -> c.sessions().of(m).stream()
                .filter(AgentSession::isOpen)
                .findFirst()
                .orElseThrow()
                .agent());
        boolean atWork = office.query(c -> c.queries().atWork(worker));
        assertThat(atWork).isTrue();

        JsonPath preview =
                api().get("/api/agents/dismiss-all-but-core").then().extract().jsonPath();
        assertThat(entry(preview, worker.value()))
                .containsEntry("fate", "BLOCKED")
                .containsEntry("code", "AGENT_BUSY");
        api().body("{\"agents\":[\"" + worker.value() + "\"]}")
                .post("/api/agents/dismiss-all-but-core")
                .then()
                .statusCode(200)
                .body("outcomes[0].result", equalTo("REFUSED"))
                .body("outcomes[0].code", equalTo("AGENT_BUSY"));
        boolean dismissed =
                office.query(c -> c.staff().find(worker).orElseThrow().dismissed());
        assertThat(dismissed).isFalse();

        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + m.value() + "/cancel");
        // The cancelled mission's session still plays to its end: this test's work is over once it has.
        SchedulerQuiet.await(scheduler);
        boolean open = office.query(c -> c.sessions().of(m).stream().anyMatch(AgentSession::isOpen));
        assertThat(open).as("the session ended").isFalse();
    }

    void allow(MissionId m) {
        List<Question> open = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .toList());
        for (Question q : open) {
            api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q.id().value() + "/answer");
        }
    }

    @Test
    void anAgentsCredentialIsRefusedOnEveryCoreRoute() {
        String credential = hooks.register(new AgentRuntime.Governor() {
            @Override
            public AgentRuntime.Verdict decide(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public void completed(dev.floor42.server.runtime.RuntimeEvent.ToolCompleted result) {}
        });
        try {
            var agent = RestAssured.given()
                    .header("Authorization", "Bearer " + credential)
                    .contentType("application/json");
            agent.body("{\"core\":true}").put("/api/agents/chris/core").then().statusCode(403);
            RestAssured.given()
                    .header("Authorization", "Bearer " + credential)
                    .get("/api/agents/dismiss-all-but-core")
                    .then()
                    .statusCode(403);
            RestAssured.given()
                    .header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"agents\":[\"chris\"]}")
                    .post("/api/agents/dismiss-all-but-core")
                    .then()
                    .statusCode(403);
        } finally {
            hooks.unregister(credential);
        }
        Boolean starred = office.query(
                c -> c.staff().find(new AgentId("chris")).map(a -> a.core()).orElse(false));
        assertThat(starred).isFalse();
    }
}
