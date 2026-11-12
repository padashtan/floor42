package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.MissionEvent.MissionCreated;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The progress panel tells the director what waits for them: here, a plan to approve. */
@QuarkusTest
class ProgressApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    private TicketStatus statusOf(TicketId t) {
        return office.query(c -> c.tickets().find(t).orElseThrow().status());
    }

    @Test
    void aCancelledMissionClosesItsProposedTicketAndWaitsForNothing() {
        var ctx = office.context(Actor.director(), Origin.SIMULATED);
        AgentId planner = new AgentId("cancel-planner");
        office.execute(c ->
                c.staff().hire(ctx, planner, "Planner", "role", "anthropic", "claude-sonnet-5-5", "medium", List.of()));
        MissionId m = ((MissionCreated) office.execute(c -> c.missions().create(ctx, "Cancel me", "A report"))
                        .getFirst()
                        .event())
                .mission();
        office.execute(c -> c.tickets().create(ctx, m, "First", "", Priority.NORMAL, List.of(), List.of("done")));
        office.execute(c -> c.missions().submitPlan(office.context(Actor.agent(planner), Origin.SIMULATED), m));
        office.execute(c -> c.missions().approvePlan(ctx, m));
        office.execute(c -> c.missions().enableTicketValidation(ctx, m));
        TicketId proposed = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        office.context(Actor.agent(planner), Origin.SIMULATED),
                                        m,
                                        "Proposed",
                                        "",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("done")))
                        .getFirst()
                        .event())
                .ticket();
        assertThat(statusOf(proposed)).isEqualTo(TicketStatus.PROPOSED);
        api().get("/api/progress")
                .then()
                .body("find { it.mission == '" + m + "' }.forYou.kind", hasItem("APPROVE_TICKET"));

        api().body("{\"reason\":\"stop\"}")
                .post("/api/missions/" + m + "/cancel")
                .then()
                .statusCode(200);

        assertThat(statusOf(proposed)).isEqualTo(TicketStatus.CANCELLED);
        api().get("/api/progress").then().statusCode(200).body("mission", not(hasItem(m.value())));
    }

    @Test
    void aPlanToApproveWaitsForTheDirector() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Notes\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        api().get("/api/progress")
                .then()
                .statusCode(200)
                .body("find { it.mission == '" + id + "' }.forYou.kind", hasItem("APPROVE_PLAN"));
        api().post("/api/missions/" + id + "/nudge").then().statusCode(200);
        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }
}
