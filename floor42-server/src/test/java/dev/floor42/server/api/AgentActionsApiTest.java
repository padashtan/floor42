package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** What the director's right click on an agent does: suspend, resume, change model and effort. */
@QuarkusTest
class AgentActionsApiTest extends ApiTestBase {

    @Test
    void theDirectorManagesAnAgent() {
        // The demo hires its staff; any hired agent will do.
        api().post("/api/demo").then().statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(201)));
        String agent = api().get("/api/agents").then().statusCode(200).extract().path("[0].id");

        api().body("{\"reason\":\"too expensive\"}")
                .post("/api/agents/" + agent + "/suspend")
                .then()
                .statusCode(200)
                .body("suspended", equalTo(true));
        api().post("/api/agents/" + agent + "/suspend")
                .then()
                .statusCode(409)
                .body("code", equalTo("ALREADY_SUSPENDED"));
        api().post("/api/agents/" + agent + "/resume").then().statusCode(200).body("suspended", equalTo(false));

        api().body("{\"model\":\"claude-haiku-5-5\",\"effort\":\"low\"}")
                .put("/api/agents/" + agent)
                .then()
                .statusCode(200)
                .body("model", equalTo("claude-haiku-5-5"))
                .body("effort", equalTo("low"));
        api().get("/api/journal?limit=1").then().statusCode(200).body("[0].type", equalTo("AgentReconfigured"));
        api().body("{\"model\":\"claude-haiku-5-5\",\"effort\":\"turbo\"}")
                .put("/api/agents/" + agent)
                .then()
                .statusCode(400);
        given().post("/api/agents/" + agent + "/resume").then().statusCode(401);
        // The profile: declared facts and a record measured on real missions only (the demo is simulated).
        api().get("/api/agents/" + agent + "/profile")
                .then()
                .statusCode(200)
                .body("model", equalTo("claude-haiku-5-5"))
                .body("record.sessions", org.hamcrest.Matchers.greaterThanOrEqualTo(0))
                .body("modelTrait", org.hamcrest.Matchers.containsString("cheapest"));
        api().get("/api/agents/nobody/profile").then().statusCode(404);
    }
}
