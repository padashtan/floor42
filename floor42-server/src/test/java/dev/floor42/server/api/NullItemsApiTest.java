package dev.floor42.server.api;

import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** A null inside a list of a request body is a 400 with the usual body, whatever the route (found by API fuzzing). */
@QuarkusTest
class NullItemsApiTest extends ApiTestBase {

    @Test
    void aNullCriterionOfATicketEditIsA400NotAServerError() {
        api().body("{\"title\":\"t\",\"acceptanceCriteria\":[null]}")
                .put("/api/tickets/T-1")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    void aNullMissionIdOfAnArchiveRequestIsA400NotA404() {
        api().body("{\"missions\":[null]}")
                .post("/api/missions/archive")
                .then()
                .statusCode(400)
                .body("code", equalTo("BAD_REQUEST"));
    }

    @Test
    void aMalformedMissionIdInACostsPathIsA404() {
        api().get("/api/missions/a%20b/costs").then().statusCode(404);
    }

    @Test
    void aMethodARouteDoesNotServeIsA405ThatSaysWhichOnesItServes() {
        api().delete("/api/agents")
                .then()
                .statusCode(405)
                .header("Allow", org.hamcrest.Matchers.containsString("POST"));
    }
}
