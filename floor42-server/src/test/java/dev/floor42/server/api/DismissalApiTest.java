package dev.floor42.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The director dismisses whoever is not at work right now (reported 2026-10-31: "impossible to dismiss people"). Every
 * built-in committee member was refused, Chris included since his id is the developer seat's; only the two chairs stay.
 */
@QuarkusTest
class DismissalApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    LiveMissions live;

    @Inject
    dev.floor42.server.app.StaffProvisioner staff;

    @Test
    void aCommitteeMemberNotAtWorkLeavesAndTheirSeatIsVacant() {
        // Basile is hired the way Floor42 hires a committee member on first need; no other test seats him.
        AgentId basile = new AgentId("basile");
        if (office.query(c -> c.staff().find(basile).isEmpty())) {
            office.execute(c -> c.staff()
                    .hire(
                            office.context(Actor.director(), Origin.REAL),
                            basile,
                            "Basile",
                            "Operations and observability specialist",
                            "anthropic",
                            "claude-haiku-5-5",
                            "medium",
                            List.of()));
        }
        api().get("/api/agents")
                .then()
                .body("find { it.id == 'basile' }.atWork", equalTo(false))
                .body("find { it.id == 'basile' }.stays", equalTo(false));

        api().body("{\"reason\":\"not needed\"}")
                .post("/api/agents/basile/dismiss")
                .then()
                .statusCode(200);
        api().get("/api/agents").then().body("id", not(hasItem("basile")));

        // The seat stays listed, vacant; choosing it says why it cannot be, before any mission is created.
        api().get("/api/committee/roles")
                .then()
                .body("find { it.key == 'operations' }.vacant", equalTo(true))
                .body("find { it.key == 'security' }.vacant", equalTo(false));
        int missions = office.query(c -> c.missions().all().size());
        api().body("{\"title\":\"Deploy\",\"objective\":\"x\",\"repository\":\".\",\"committee\":[\"operations\"]}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"))
                .body("message", containsString("Basile has left the company"));
        int after = office.query(c -> c.missions().all().size());
        org.assertj.core.api.Assertions.assertThat(after)
                .as("no mission created")
                .isEqualTo(missions);
    }

    @Test
    void theTwoChairsStayAndSayWhy() {
        staff.ensurePlanner();
        api().get("/api/agents").then().body("find { it.id == 'geoffrey' }.stays", equalTo(true));
        for (String chair : List.of("geoffrey", "lucie")) {
            api().body("{\"reason\":\"test\"}")
                    .post("/api/agents/" + chair + "/dismiss")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("AGENT_NEEDED"))
                    .body("message", containsString("lighter model"));
        }
    }
}
