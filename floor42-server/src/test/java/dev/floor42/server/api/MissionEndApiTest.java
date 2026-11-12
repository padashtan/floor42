package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.Workspace;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The end-of-mission report (ADR-0039) with the fake Claude Code: a producer leaves a file it did not commit and an
 * output git ignores (like a mission PDFs); the report names both with the worktree's path, says the basis of each
 * figure, and offers each next step with the refusal it would meet, before and after the close.
 */
@QuarkusTest
class MissionEndApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    JsonPath report(MissionId m) {
        return api().get("/api/missions/" + m.value() + "/report")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    static Map<String, Object> step(JsonPath r, String kind) {
        return r.getMap("next.find { it.kind == '" + kind + "' }");
    }

    @Test
    void theReportSaysWhatIsInNoCommitAndWhatComesNext() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        Files.writeString(repo.resolve(".gitignore"), "out/\n");
        LiveMissionTest.run(repo, "git", "add", ".gitignore");
        LiveMissionTest.run(
                repo, "git", "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "ignore");
        MissionId m = new MissionId(api().body("{\"title\":\"Reports\",\"objective\":\"Describe this repository"
                        + " [fake:touch=notes.txt]\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
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

        JsonPath open = report(m);
        assertThat(step(open, "CLOSE")).containsEntry("available", false).containsEntry("code", "MISSION_NOT_FINISHED");

        await("the reviewed ticket", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE && c(m).isEmpty();
        });
        List<Workspace> spaces = office.query(c -> c.workshop().workspaces(m));
        Workspace producer = spaces.getFirst();
        Path worktree = Path.of(producer.path());
        // An output the producer made where git ignores it, like a mission output under docs/out/*.pdf.
        Files.createDirectories(worktree.resolve("out"));
        Files.writeString(worktree.resolve("out/report.pdf"), "%PDF-1.4 fake");

        JsonPath r = report(m);
        assertThat(r.getString("status")).isEqualTo("IN_PROGRESS");
        assertThat(r.getString("totals.costUsd.basis")).isEqualTo("ESTIMATED");
        assertThat(r.getString("totals.workMs.basis")).isEqualTo("MEASURED");
        assertThat(r.getDouble("totals.workMs.value")).isPositive();
        assertThat(r.getInt("totals.done")).isEqualTo(1);
        assertThat(r.getString("tickets[0].status")).isEqualTo("DONE");
        assertThat(r.getList("tickets[0].reviews")).isNotEmpty();
        assertThat(r.getString("tickets[0].reviews[0].verdict")).isNotBlank();
        assertThat(r.getString("tickets[0].reopenTo")).isEqualTo(r.getString("tickets[0].id"));

        Map<String, Object> out =
                r.getMap("outputs.find { it.agent == '" + producer.agent().value() + "' }");
        assertThat(out).containsEntry("path", producer.path()).containsEntry("readable", true);
        assertThat(r.getList("outputs.find { it.agent == '" + producer.agent().value() + "' }.uncommitted"))
                .contains("notes.txt");
        assertThat(r.getList("outputs.find { it.agent == '" + producer.agent().value() + "' }.ignored"))
                .containsExactly("out/report.pdf");

        // Loudest first: what no commit, merge or pull request will carry.
        assertThat(r.getString("warnings[0].code")).isEqualTo("OUTPUTS_IGNORED");
        assertThat(r.getString("warnings[0].path")).isEqualTo(producer.path());
        assertThat(r.getList("warnings.code", String.class)).contains("OUTPUTS_NOT_COMMITTED", "NO_CHECKS");

        assertThat(step(r, "CLOSE")).containsEntry("available", true);
        assertThat(step(r, "REOPEN")).containsEntry("available", true);
        assertThat(step(r, "PULL_REQUEST")).containsEntry("available", false).containsEntry("code", "NOTHING_ACCEPTED");
        assertThat(step(r, "MERGE")).containsEntry("available", false);
        assertThat(step(r, "FOLLOW_UP")).containsEntry("available", true);
        assertThat(r.getString("followUp.repository")).isEqualTo(repo.toString());
        assertThat(r.getString("followUp.objective"))
                .contains("ignored by git in " + producer.path())
                .contains("out/report.pdf");

        // The report said what is in no commit; the director accepts it knowingly (ADR-0055).
        String seen = api().get("/api/missions/" + m.value() + "/uncommitted")
                .then()
                .statusCode(200)
                .body("clean", org.hamcrest.Matchers.equalTo(false))
                .extract()
                .path("token");
        api().body("{\"reason\":\"delivered\",\"acknowledged\":\"" + seen + "\"}")
                .post("/api/missions/" + m.value() + "/complete")
                .then()
                .statusCode(200);
        JsonPath closed = report(m);
        assertThat(closed.getString("status")).isEqualTo("COMPLETED");
        assertThat(closed.getString("outcome")).isEqualTo("delivered");
        assertThat(step(closed, "CLOSE")).containsEntry("available", false);
        assertThat(step(closed, "REOPEN"))
                .containsEntry("available", false)
                .containsEntry("code", "MISSION_NOT_ACTIVE");
        assertThat(closed.getString("tickets[0].reopenRefusedCode")).isEqualTo("MISSION_NOT_ACTIVE");
        // Closing does not make the outputs safe: the report still says where they are.
        assertThat(closed.getString("warnings[0].code")).isEqualTo("OUTPUTS_IGNORED");

        api().get("/api/missions/M-99999/report").then().statusCode(404);
    }

    private List<String> c(MissionId m) {
        return office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.isOpen())
                .map(s -> s.id())
                .toList());
    }
}
