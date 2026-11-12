package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.server.api.PermissionViews.ToolSettingView;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ToolSettingsApiTest {

    @Inject
    SessionToken token;

    @Inject
    Journal journal;

    String auth() {
        return "Bearer " + token.value();
    }

    String demo() {
        return given().header("Authorization", auth())
                .post("/api/demo")
                .then()
                .extract()
                .path("mission");
    }

    List<ToolSettingView> table(String mission) {
        return List.of(given().header("Authorization", auth())
                .get("/api/missions/" + mission + "/tools")
                .then()
                .statusCode(200)
                .extract()
                .as(ToolSettingView[].class));
    }

    ToolSettingView row(String mission, String tool) {
        return table(mission).stream()
                .filter(r -> r.tool().equals(tool))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theTableShowsTheDecisionInForceItsSourceAndRememberedAnswers() {
        String m = demo();

        List<ToolSettingView> rows = table(m);

        assertThat(rows)
                .extracting(ToolSettingView::tool)
                .containsExactly(
                        "Read", "Glob", "Grep", "Write", "Edit", "Bash", "WebFetch", "WebSearch", "Skill", "Agent");
        ToolSettingView write = row(m, "Write");
        assertThat(write.decision()).isEqualTo("ASK");
        assertThat(write.source()).contains("mission rule");
        assertThat(write.remembered()).hasSize(1);
        assertThat(write.remembered().getFirst().agent()).isEqualTo("chris");
        assertThat(write.remembered().getFirst().answer()).isEqualTo("DENY");
        assertThat(row(m, "WebFetch").decision()).isEqualTo("DENY");
        assertThat(row(m, "WebFetch").source()).contains("default");
    }

    @Test
    void theDirectorForgetsARememberedAnswer() {
        String m = demo();
        String q = row(m, "Write").remembered().getFirst().question();

        given().header("Authorization", auth())
                .post("/api/questions/" + q + "/forget")
                .then()
                .statusCode(200);

        assertThat(row(m, "Write").remembered()).isEmpty();
        given().header("Authorization", auth())
                .post("/api/questions/" + q + "/forget")
                .then()
                .statusCode(409);
        given().post("/api/questions/" + q + "/forget").then().statusCode(401);
    }

    @Test
    void theDirectorSetsALineAndItIsTracedInTheJournal() {
        String m = demo();

        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"decision\":\"ALLOW\"}")
                .put("/api/missions/" + m + "/tools/Bash")
                .then()
                .statusCode(200)
                .body("decision", equalTo("ALLOW"))
                .body("source", equalTo("your setting"))
                .body(
                        "warning",
                        org.hamcrest.Matchers.startsWith("Full shell access: every command runs without asking"));

        var last = journal.latest(1).getFirst().envelope();
        assertThat(last.type()).isEqualTo("PermissionRuleAdded");
        assertThat(last.actor().isDirector()).isTrue();
    }

    @Test
    void invalidRequestsAreRefusedClearly() {
        String m = demo();

        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"decision\":\"SOMETIMES\"}")
                .put("/api/missions/" + m + "/tools/Write")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_DECISION"));
        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"decision\":\"ALLOW\"}")
                .put("/api/missions/" + m + "/tools/Teleport")
                .then()
                .statusCode(404);
    }
}
