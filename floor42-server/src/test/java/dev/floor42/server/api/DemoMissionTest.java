package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Company;
import dev.floor42.domain.MissionId;
import dev.floor42.server.api.ActionViews.ActionView;
import dev.floor42.server.api.MissionViews.MissionView;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The I2 acceptance test: a mission replayed end to end, governed, persisted, and rebuilt identically from disk. */
@QuarkusTest
class DemoMissionTest {

    @Inject
    SessionToken token;

    @Inject
    Journal journal;

    @Inject
    OfficeService office;

    String bearer() {
        return "Bearer " + token.value();
    }

    @Test
    void aReplayedMissionRunsUnderGovernanceAndSurvivesARestart() {
        String mission = given().header("Authorization", bearer())
                .when()
                .post("/api/demo")
                .then()
                .statusCode(200)
                .extract()
                .path("mission");

        MissionView view = given().header("Authorization", bearer())
                .when()
                .get("/api/missions/" + mission)
                .then()
                .statusCode(200)
                .extract()
                .as(MissionView.class);

        assertThat(view.origin()).isEqualTo("SIMULATED");
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(view.tickets()).extracting(TicketViews.TicketView::status).containsExactly("DONE", "TODO");
        assertThat(view.tickets().getFirst().reviews())
                .singleElement()
                .extracting(TicketViews.ReviewView::reviewer)
                .isEqualTo("darius");

        List<ActionView> actions = view.actions();
        assertThat(actions).extracting(ActionView::tool).containsExactly("Bash", "Write", "Write", "Read");
        ActionView gitStatus = actions.get(0);
        assertThat(gitStatus.summary()).isEqualTo("git status");
        assertThat(gitStatus.stage()).isEqualTo("CONFIRMED");
        assertThat(gitStatus.ran()).isTrue();
        ActionView write = actions.get(1);
        assertThat(write.stage()).isEqualTo("DENIED");
        assertThat(write.denialReason()).isEqualTo("DIRECTOR");
        assertThat(write.ran()).isFalse();
        assertThat(write.summary()).isEqualTo("notes.txt");
        // Without waiting, the second write arrives while Q-1 is still open: it waits for that same answer.
        ActionView secondWrite = actions.get(2);
        assertThat(secondWrite.summary()).isEqualTo("summary.txt");
        assertThat(secondWrite.stage()).isEqualTo("DENIED");
        assertThat(secondWrite.decisionBasis()).isEqualTo(write.decisionBasis());
        assertThat(actions.get(3).stage()).isEqualTo("EXECUTED");

        assertThat(view.questions())
                .extracting(QuestionViews.QuestionView::kind, QuestionViews.QuestionView::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ACTION_APPROVAL", "ANSWERED"),
                        org.assertj.core.groups.Tuple.tuple("OPEN", "OPEN"));

        // Restart: a company rebuilt from what is on disk is indistinguishable from the live one.
        Company fromDisk = Company.replay(journal.readAll());
        MissionId id = new MissionId(mission);
        MissionView rebuilt =
                MissionView.of(fromDisk, fromDisk.missions().find(id).orElseThrow());
        assertThat(rebuilt).isEqualTo(view);
        assertThat(fromDisk.version()).isEqualTo(office.query(Company::version));
    }

    @Test
    void theDirectorAnswersFromTheDeskAndRefusalsAreExplained() {
        String mission = given().header("Authorization", bearer())
                .post("/api/demo")
                .then()
                .extract()
                .path("mission");
        String question = given().header("Authorization", bearer())
                .get("/api/missions/" + mission)
                .then()
                .extract()
                .path("questions.find { it.status == 'OPEN' }.id");

        given().header("Authorization", bearer())
                .contentType("application/json")
                .body("{\"answer\":\"Maybe\"}")
                .post("/api/questions/" + question + "/answer")
                .then()
                .statusCode(400)
                .body("code", org.hamcrest.Matchers.equalTo("INVALID_ANSWER"));

        given().header("Authorization", bearer())
                .contentType("application/json")
                .body("{\"answer\":\"yes\"}")
                .post("/api/questions/" + question + "/answer")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("ANSWERED"))
                .body("answer", org.hamcrest.Matchers.equalTo("Yes"));
    }

    @Test
    void theJournalExposesProvenanceOfEveryEvent() {
        String demo = given().header("Authorization", bearer())
                .post("/api/demo")
                .then()
                .statusCode(200)
                .extract()
                .path("mission");

        List<JournalViews.JournalEntryView> entries = List.of(given().header("Authorization", bearer())
                .get("/api/journal?limit=500")
                .then()
                .statusCode(200)
                .extract()
                .as(JournalViews.JournalEntryView[].class));

        assertThat(entries).isNotEmpty().allMatch(e -> e.origin() != null && e.actor() != null);
        assertThat(entries)
                .filteredOn(e -> e.type().equals("ActionAuthorized") && demo.equals(e.mission()))
                .as("the journal is shared by every test of the run: only the demo's own authorizations")
                .isNotEmpty()
                .allMatch(e -> e.actor().equals("system:floor42") && e.authorizationRef() != null);
    }

    @Test
    void theDirectorCanAbandonADemoThatCanNeverBeClosed() {
        String mission = given().header("Authorization", bearer())
                .when()
                .post("/api/demo")
                .then()
                .statusCode(200)
                .extract()
                .path("mission");

        given().header("Authorization", bearer())
                .contentType("application/json")
                .body("{\"reason\":\"done looking\"}")
                .when()
                .post("/api/missions/" + mission + "/complete")
                .then()
                .statusCode(409);

        MissionView view = given().header("Authorization", bearer())
                .contentType("application/json")
                .body("{\"reason\":\"done looking\"}")
                .when()
                .post("/api/missions/" + mission + "/cancel")
                .then()
                .statusCode(200)
                .extract()
                .as(MissionView.class);
        assertThat(view.status()).isEqualTo("CANCELLED");

        given().header("Authorization", bearer())
                .contentType("application/json")
                .body("{\"reason\":\"again\"}")
                .when()
                .post("/api/missions/" + mission + "/cancel")
                .then()
                .statusCode(409);
    }
}
