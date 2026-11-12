package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.Committee;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.PermissionRule;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Seen live (Q-73, Q-74): the director allowed writing on a committee mission and was still asked. End to end with the
 * fake Claude Code, through the real hooks: a choice made before the ruling stays the director's, the ruling's option
 * says what writing will be, and a choice made after it reaches the implementation ticket.
 */
@QuarkusTest
class PermissionsAppliedApiTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    io.restassured.specification.RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value()).contentType("application/json");
    }

    void set(String mission, String tool, String decision) {
        api().body("{\"decision\":\"" + decision + "\"}")
                .put("/api/missions/" + mission + "/tools/" + tool)
                .then()
                .statusCode(200);
    }

    Question open(MissionId m, QuestionKind kind) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN && q.kind() == kind)
                .findFirst()
                .orElse(null));
    }

    @Test
    void theDirectorsChoicesApplyToTheImplementationThroughTheHooks() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body(
                        "{\"title\":\"Notes\",\"objective\":\"Should the project keep notes? [fake:write-edit]\","
                                + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\","
                                + "\"committee\":[\"developer\"]}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        // Before the ruling: the director allows Edit for the mission. Write keeps the committee's line.
        set(id, "Edit", "ALLOW");
        await(
                "the decision record",
                Duration.ofSeconds(60),
                () -> office.query(c -> c.committees().latest(m).orElseThrow().stage())
                        == Committee.Stage.AWAITING_DIRECTOR);

        // The ruling's options read the table as it stood when it was asked.
        Question ruling = open(m, QuestionKind.COMMITTEE_ARBITRATION);
        String implement = ruling.options().stream()
                .filter(o -> o.startsWith("Adopt and implement ("))
                .findFirst()
                .orElseThrow();
        assertThat(implement)
                .as("Write asks (the defaults), Edit is allowed (the director's line)")
                .startsWith("Adopt and implement (writes per your permissions, tool by tool)");
        api().get("/api/questions")
                .then()
                .statusCode(200)
                .body(
                        "find { it.id == '" + ruling.id().value() + "' }.writesAsked",
                        org.hamcrest.Matchers.equalTo("MIXED"))
                .body(
                        "find { it.id == '" + ruling.id().value() + "' }.writesNow",
                        org.hamcrest.Matchers.equalTo("MIXED"));

        api().body("{\"answer\":\"" + implement + "\"}")
                .post("/api/questions/" + ruling.id().value() + "/answer")
                .then()
                .statusCode(200);
        await(
                "the implementation ticket",
                Duration.ofSeconds(20),
                () -> office.query(c -> c.tickets().of(m).size() == 2
                        && c.tickets().of(m).get(1).assignee() != null));
        TicketId implementation = office.query(c -> c.tickets().of(m).get(1).id());
        List<PermissionRule> ticketRules =
                office.query(c -> c.missions().find(m).orElseThrow().policy().rules().stream()
                        .filter(r -> implementation.equals(r.ticket()))
                        .toList());
        assertThat(ticketRules)
                .as("the director's Edit line is not overridden by the defaults; Write gets the default")
                .extracting(PermissionRule::tool)
                .contains("Write")
                .doesNotContain("Edit");

        // After the ruling: the director allows Write. The developer then writes, and edits (B1, through the hook).
        set(id, "Write", "ALLOW");
        api().body("{\"tickets\":[\"" + implementation.value() + "\"]}")
                .post("/api/missions/" + id + "/tickets/approve")
                .then()
                .statusCode(200);
        await(
                "the developer's Write and Edit",
                Duration.ofSeconds(60),
                () -> executed(m, implementation, "Write") && executed(m, implementation, "Edit"));
        List<Question> approvals = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.ACTION_APPROVAL)
                .toList());
        assertThat(approvals).as("no question for the write or the edit").isEmpty();

        api().body("{\"reason\":\"test done\"}").post("/api/missions/" + id + "/cancel");
    }

    boolean executed(MissionId m, TicketId t, String tool) {
        Action call = office.query(c -> c.actions().of(m).stream()
                .filter(a -> t.equals(a.ticket()) && a.tool().equals(tool))
                .findFirst()
                .orElse(null));
        return call != null && call.stage() == ActionStage.EXECUTED;
    }
}
