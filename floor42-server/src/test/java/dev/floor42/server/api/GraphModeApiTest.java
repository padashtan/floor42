package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.server.app.Briefs;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The graph mode end to end (ADR-0062): chosen at the creation, pinned in the workflow, planned with a test, a risk and
 * a tier per node, and capped at one rework per node.
 */
@QuarkusTest
class GraphModeApiTest extends DispatchFixture {

    private MissionId create(String objective, String extra) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        return new MissionId(api().body("{\"title\":\"Graph\",\"objective\":\"" + objective + "\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"" + extra + "}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
    }

    private MissionId planned(String extra) throws Exception {
        MissionId m = create("Describe this repository [fake:graph-plan]", extra);
        approve(m);
        await(
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).size() == 3));
        return m;
    }

    private List<Ticket> nodes(MissionId m) {
        return office.query(c -> c.tickets().of(m).subList(1, 3));
    }

    @Test
    void theGraphModeNeedsThePlannerAndNothingElseIsAMode() throws Exception {
        String repo = LiveMissionTest.gitRepository().toString().replace("\\", "\\\\");
        String base = "{\"title\":\"Graph\",\"objective\":\"x\",\"repository\":\"" + repo + "\"";
        api().body(base + ",\"mode\":\"graph\",\"plan\":false}")
                .post("/api/missions")
                .then()
                .statusCode(409)
                .body("code", equalTo("GRAPH_NEEDS_PLAN"));
        api().body(base + ",\"mode\":\"swarm\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void aGraphMissionPinsTheModeAndTheNodesCarryTheirTestRiskAndTier() throws Exception {
        MissionId m = planned(",\"plan\":true,\"mode\":\"graph\"");
        MissionWorkflow pin = office.query(c -> c.workflows().of(m));
        assertThat(pin)
                .isInstanceOfSatisfying(
                        MissionWorkflow.Pinned.class,
                        p -> assertThat(p.workflow().graph()).isTrue());
        api().get("/api/missions/" + m.value()).then().statusCode(200).body("workflow.workflow.mode", equalTo("graph"));

        List<Ticket> nodes = nodes(m);
        assertThat(nodes.get(1).dependsOn()).containsExactly(nodes.get(0).id());
        assertThat(nodes.get(0).acceptanceCriteria())
                .containsExactly("summary written", "Test: cat SUMMARY.md => it prints the summary");
        assertThat(nodes.get(1).acceptanceCriteria()).containsExactly("licenses listed");
        assertThat(nodes.get(0).description())
                .contains("Risk: low: it only reads")
                .contains("Tier: light");
        assertThat(nodes.get(1).description()).contains("Risk: high: a wrong licence is costly");
        assertThat(nodes.get(1).description()).contains("Tier: standard");
        assertThat(brief(m)).contains("TEST:").contains("RISK:").contains("TIER:");
        cancel(m);
    }

    @Test
    void aLoopMissionReadsTheSamePlanAsBefore() throws Exception {
        MissionId m = planned(",\"plan\":true");
        api().get("/api/missions/" + m.value()).then().statusCode(200).body("workflow.workflow.mode", equalTo("loop"));
        List<Ticket> nodes = nodes(m);
        assertThat(nodes.get(0).acceptanceCriteria()).containsExactly("summary written");
        assertThat(nodes.get(0).description()).doesNotContain("Risk:").doesNotContain("Tier:");
        assertThat(brief(m)).doesNotContain("TEST:").doesNotContain("RISK:").doesNotContain("TIER:");
        cancel(m);
    }

    @Test
    void aGraphNodeIsReworkedOnceThenGoesToTheDirector() throws Exception {
        MissionId m = create("Write notes [fake:graph-plan] [fake:rejected]", ",\"plan\":true,\"mode\":\"graph\"");
        approve(m);
        await(
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).size() == 3));
        api().body("{\"tickets\":[\"" + nodes(m).get(0).id().value() + "\"]}")
                .post("/api/missions/" + m.value() + "/tickets/approve")
                .then()
                .statusCode(200);
        Supplier<Question> rejected = () -> office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN
                        && q.phrase() != null
                        && q.phrase().code().equals("rejectedTimes"))
                .findFirst()
                .orElse(null));
        await("the node is rejected twice", Duration.ofSeconds(90), () -> {
            allowActions(m);
            return rejected.get() != null;
        });
        assertThat(rejected.get().phrase().params()).containsEntry("times", "2");
        Ticket first = nodes(m).get(0);
        assertThat(first.reviews().stream()
                        .filter(r -> r.verdict() == dev.floor42.domain.ReviewVerdict.REJECTED)
                        .count())
                .as("the first rejection reopened the node, the second asked")
                .isEqualTo(2);
        cancel(m);
    }

    private String brief(MissionId m) {
        return office.query(c -> Briefs.planner(
                c,
                c.missions().find(m).orElseThrow(),
                c.tickets().of(m).getFirst(),
                c.staff().find(new AgentId("galen")).orElseThrow()));
    }
}
