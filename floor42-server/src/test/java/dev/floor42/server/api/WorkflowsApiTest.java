package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.WorkflowEvent.WorkflowPinned;
import dev.floor42.domain.workflow.MissionWorkflow;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Company workflows and the pin (ADR-0050), end to end with the fake Claude Code: the director writes a workflow, a
 * mission pins it with its creation, later edits never reach it, the plan step's limit is the pinned one, and the
 * planner's proposal reaches the list for the director to adopt or dismiss.
 */
@QuarkusTest
class WorkflowsApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    /** A name of its own per test: the company's list is shared by the tests of the run. */
    private static String unique(String name) {
        return name + " " + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String save(String name, String steps, boolean replace) {
        return "{\"name\":\"" + name + "\",\"steps\":[" + steps + "],\"replace\":" + replace + "}";
    }

    private static final String TWO_TICKETS = "{\"step\":\"plan\",\"maxTickets\":2},{\"step\":\"approval\"},"
            + "{\"step\":\"produce\"},{\"step\":\"review\"},{\"step\":\"accept\"}";
    private static final String DECIDE = "{\"step\":\"approval\"},{\"step\":\"committee\"},{\"step\":\"accept\"}";

    private String mission(String objective, String workflow, boolean plan, String committee) throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        return api().body("{\"title\":\"Pinned\",\"objective\":\"" + objective + "\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\",\"plan\":" + plan
                        + (committee == null ? "" : ",\"committee\":[\"" + committee + "\"]")
                        + (workflow == null ? "" : ",\"workflow\":\"" + workflow + "\"") + "}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void theDirectorWritesAWorkflowAndTheReaderRefusesWhatItWouldRefuseInAPin() {
        String name = unique("Light");
        api().body(save(name, TWO_TICKETS, false))
                .post("/api/workflows")
                .then()
                .statusCode(200)
                .body("builtIn.name", equalTo("built-in"))
                .body("workflows.name", hasItem(name));
        api().body(save(name, DECIDE, false))
                .post("/api/workflows")
                .then()
                .statusCode(409)
                .body("code", equalTo("WORKFLOW_EXISTS"));
        api().body(save(name, DECIDE, true)).post("/api/workflows").then().statusCode(200);
        api().body(save(unique("Bad"), "{\"step\":\"approval\"},{\"step\":\"produce\"},{\"step\":\"accept\"}", false))
                .post("/api/workflows")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_WORKFLOW"))
                .body("message", containsString("Produced work is reviewed"));
        api().body(save(unique("Bad"), "{\"step\":\"plan\",\"maxTickets\":100}," + DECIDE, false))
                .post("/api/workflows")
                .then()
                .statusCode(400)
                .body("message", containsString("more than 2 digits"));
        // Nothing is dropped in silence: an unknown key, auto-acceptance included, is refused as the pinned reader
        // does.
        api().body(save(
                        unique("Auto"),
                        "{\"step\":\"approval\"},{\"step\":\"committee\"},{\"step\":\"accept\",\"autoAccept\":true}",
                        false))
                .post("/api/workflows")
                .then()
                .statusCode(400)
                .body("message", containsString("Auto-acceptance is not built yet"));
        api().body(save(
                        unique("Colour"),
                        "{\"step\":\"approval\",\"colour\":\"red\"},{\"step\":\"committee\"},{\"step\":\"accept\"}",
                        false))
                .post("/api/workflows")
                .then()
                .statusCode(400)
                .body("message", containsString("unknown key \"colour\""));
        api().body("{\"name\":\"" + unique("Extra") + "\",\"steps\":[" + DECIDE + "],\"autoAccept\":true}")
                .post("/api/workflows")
                .then()
                .statusCode(400)
                .body("message", containsString("Unknown field \"autoAccept\""));
        api().body("{\"name\":\"" + name + "\"}")
                .post("/api/workflows/remove")
                .then()
                .statusCode(200);
        api().body("{\"name\":\"" + name + "\"}")
                .post("/api/workflows/remove")
                .then()
                .statusCode(404);
    }

    /** The pin is written right after MissionCreated, and a later edit of the workflow never reaches the mission. */
    @Test
    void aMissionPinsItsWorkflowWithItsCreationAndKeepsIt() throws Exception {
        String name = unique("Two");
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        String id = mission("Describe this repository", name, true, null);
        MissionId m = new MissionId(id);

        List<EventEnvelope> all = journal.readAll();
        int created = indexOf(
                all, e -> e.event() instanceof MissionCreated c && c.mission().equals(m));
        assertThat(all.get(created + 1).event()).isInstanceOf(WorkflowPinned.class);
        assertThat(((WorkflowPinned) all.get(created + 1).event()).mission()).isEqualTo(m);

        api().body(save(name, DECIDE, true)).post("/api/workflows").then().statusCode(200);
        api().get("/api/missions/" + id)
                .then()
                .body("workflow.pinned", equalTo(true))
                .body("workflow.workflow.name", equalTo(name))
                .body("workflow.workflow.steps[0].maxTickets", equalTo(2))
                .body("tickets[0].acceptanceCriteria[0]", equalTo("At most 2 tickets, in order"));
        assertThat((Object) office.<MissionWorkflow>query(c -> c.workflows().of(m)))
                .isInstanceOf(MissionWorkflow.Pinned.class);
    }

    /** The planner's limit is the pinned one: the fake planner's three tickets are more than this workflow's two. */
    @Test
    void thePlansLimitIsThePinnedWorkflowsOwn() throws Exception {
        String name = unique("Two");
        api().body(save(name, TWO_TICKETS, false)).post("/api/workflows").then().statusCode(200);
        String id = mission("Describe this repository", name, true, null);
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);

        await(
                "the retry question",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.desk().of(m).stream()
                        .anyMatch(q -> q.status() == QuestionStatus.OPEN
                                && q.context() != null
                                && q.context().contains("more than 2"))));
        assertThat(office.<List<dev.floor42.domain.Ticket>>query(
                        c -> c.tickets().of(m)))
                .hasSize(1);
    }

    @Test
    void whatTheDirectorAsksMustBeAStepOfTheChosenWorkflow() throws Exception {
        String decide = unique("Decide");
        String light = unique("Light");
        api().body(save(decide, DECIDE, false)).post("/api/workflows").then().statusCode(200);
        api().body(save(light, TWO_TICKETS, false))
                .post("/api/workflows")
                .then()
                .statusCode(200);
        String repo = LiveMissionTest.gitRepository().toString().replace("\\", "\\\\");
        String body = "{\"title\":\"t\",\"objective\":\"o\",\"repository\":\"" + repo + "\"";

        api().body(body + ",\"workflow\":\"Nobody wrote it\"}")
                .post("/api/missions")
                .then()
                .statusCode(404)
                .body("code", equalTo("UNKNOWN_WORKFLOW"));
        api().body(body + ",\"workflow\":\"" + decide + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(409)
                .body("code", equalTo("WORKFLOW_PRODUCES_NOTHING"));
        api().body(body + ",\"plan\":true,\"workflow\":\"" + decide + "\",\"committee\":[\"architect\"]}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("message", containsString("no plan step"));
        api().body(body + ",\"workflow\":\"" + light + "\",\"committee\":[\"architect\"]}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("message", containsString("no committee step"));
    }

    /** The planner proposes on its plan's line; Floor42 records what it built, the director adopts or dismisses. */
    @Test
    void thePlannersProposalReachesTheListAndTheDirectorAdoptsIt() throws Exception {
        String id = mission("Describe [fake:propose-workflow]", null, true, null);
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await(
                "the proposal",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.workflows().proposals().stream()
                        .anyMatch(p -> p.mission().equals(m))));
        int number = office.query(c -> c.workflows().proposals().stream()
                .filter(p -> p.mission().equals(m))
                .findFirst()
                .orElseThrow()
                .number());
        assertThat((Object) office.<MissionWorkflow>query(c -> c.workflows().of(m)))
                .as("never the mission's own")
                .isInstanceOf(MissionWorkflow.Pinned.class);

        String adopted = unique("Quick read");
        api().body("{\"name\":\"" + adopted + "\",\"steps\":[" + TWO_TICKETS + "],\"proposal\":" + number + "}")
                .post("/api/workflows")
                .then()
                .statusCode(200)
                .body("workflows.name", hasItem(adopted));
        api().get("/api/workflows")
                .then()
                .body("proposals.find { it.number == " + number + " }.status", equalTo("ADOPTED"));
        api().post("/api/workflows/proposals/" + number + "/dismiss")
                .then()
                .statusCode(409)
                .body("code", equalTo("PROPOSAL_CLOSED"));
    }

    private static int indexOf(List<EventEnvelope> all, java.util.function.Predicate<EventEnvelope> p) {
        for (int i = 0; i < all.size(); i++) if (p.test(all.get(i))) return i;
        throw new AssertionError("not in the journal");
    }
}
