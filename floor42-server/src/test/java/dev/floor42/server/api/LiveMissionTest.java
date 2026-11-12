package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Action;
import dev.floor42.domain.ActionStage;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.DenialReason;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A real mission end to end, with the protocol-faithful fake of Claude Code: creation on a git repository, proposed
 * plan, director approval, producer session in its own worktree, director decision on the shell, independent review
 * by another agent, completion.
 */
@QuarkusTest
class LiveMissionTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    String auth() {
        return "Bearer " + token.value();
    }

    static Path gitRepository() throws Exception {
        Path repo = Files.createTempDirectory("floor42-repo");
        Files.writeString(repo.resolve("README.md"), "# Fake project\n");
        run(repo, "git", "init", "-q", "-b", "main");
        run(repo, "git", "add", ".");
        run(repo, "git", "-c", "user.name=test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "init");
        return repo;
    }

    static void run(Path dir, String... cmd) throws Exception {
        output(dir, cmd);
    }

    /** The command's output and errors together; a failure quotes what it said, so a refused git shows its reason. */
    static String output(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor())
                .as(String.join(" ", cmd) + " said: " + out.strip())
                .isZero();
        return out.strip();
    }

    String openQuestion(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .map(q -> q.id().value())
                .findFirst()
                .orElse(null));
    }

    TicketStatus ticketStatus(MissionId m) {
        return office.query(c -> c.tickets().of(m).getFirst().status());
    }

    @Test
    void aNewMissionStartsFromTheCompanysDefaultPermissions() throws Exception {
        try {
            given().header("Authorization", auth())
                    .contentType("application/json")
                    .body("{\"decision\":\"ASK_ALWAYS\"}")
                    .put("/api/permissions/defaults/Bash")
                    .then()
                    .statusCode(200)
                    .body("decision", equalTo("ASK_ALWAYS"))
                    .body("builtIn", equalTo("ASK"))
                    .body("changed", equalTo(true));

            String id = given().header("Authorization", auth())
                    .contentType("application/json")
                    .body("{\"title\":\"Defaults\",\"objective\":\"Describe this repository\",\"repository\":\""
                            + gitRepository().toString().replace("\\", "\\\\") + "\"}")
                    .post("/api/missions")
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("id");

            given().header("Authorization", auth())
                    .get("/api/missions/" + id + "/tools")
                    .then()
                    .statusCode(200)
                    .body("find { it.tool == 'Bash' }.decision", equalTo("ASK_ALWAYS"))
                    .body("find { it.tool == 'Read' }.decision", equalTo("ALLOW"))
                    .body("find { it.tool == 'WebFetch' }.decision", equalTo("DENY"));
            given().header("Authorization", auth())
                    .contentType("application/json")
                    .body("{\"reason\":\"Only the table was checked\"}")
                    .post("/api/missions/" + id + "/cancel")
                    .then()
                    .statusCode(200);
        } finally {
            given().header("Authorization", auth())
                    .contentType("application/json")
                    .body("{\"decision\":null}")
                    .put("/api/permissions/defaults/Bash")
                    .then()
                    .statusCode(200)
                    .body("decision", equalTo("ASK"))
                    .body("changed", equalTo(false));
        }
    }

    @Test
    void aRealMissionRunsFromPlanToIndependentReview() throws Exception {
        Path repo = gitRepository();

        String id = given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"title\":\"Inventory\",\"objective\":\"Describe this repository\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .body("origin", equalTo("REAL"))
                .body("status", equalTo("AWAITING_APPROVAL"))
                .extract()
                .path("id");
        MissionId m = new MissionId(id);

        // A paid mission is approved with its cap (or an explicit "no cap"): here a budget.
        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"budgetUsd\":5}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200)
                .body("budgetUsd", equalTo(5.0f))
                .body("capDecided", equalTo(true));

        // The conservative table asks before the first shell command: the producer waits for the director.
        await("the shell approval", Duration.ofSeconds(20), () -> openQuestion(m) != null);
        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"answer\":\"ALLOW\"}")
                .post("/api/questions/" + openQuestion(m) + "/answer")
                .then()
                .statusCode(200);

        // Writing asks once: the producer's first write waits for the director too.
        await("the write approval", Duration.ofSeconds(20), () -> openQuestion(m) != null);
        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"answer\":\"ALLOW\"}")
                .post("/api/questions/" + openQuestion(m) + "/answer")
                .then()
                .statusCode(200);

        await("the independent review", Duration.ofSeconds(30), () -> ticketStatus(m) == TicketStatus.DONE);

        Ticket ticket = office.query(c -> c.tickets().of(m).getFirst());
        assertThat(ticket.deliverable()).startsWith("Report:");
        assertThat(ticket.reviews()).singleElement().satisfies(r -> {
            assertThat(r.reviewer().value()).isEqualTo("dehren");
            assertThat(r.verdict()).isEqualTo(ReviewVerdict.VALIDATED);
        });
        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        assertThat(sessions).extracting(s -> s.agent().value()).containsExactly("chris", "dehren");
        assertThat(sessions).allMatch(s -> !s.isOpen() && !s.failed() && s.costUsdEstimated() != null);
        // The list says who worked on it (the scene keeps them at their desks while it is in progress).
        List<String> workers = given().header("Authorization", auth())
                .get("/api/missions")
                .then()
                .statusCode(200)
                .extract()
                .path("find { it.id == '" + id + "' }.agents");
        assertThat(workers).containsExactlyInAnyOrder("chris", "dehren");

        List<Action> actions = office.query(c -> c.actions().of(m));
        assertThat(actions).allMatch(a -> a.stage() != ActionStage.REQUESTED);
        assertThat(actions)
                .filteredOn(a -> a.tool().equals("Write"))
                .as("the producer writes once approved; the reviewer never writes, whatever the table says")
                .extracting(a -> a.agent().value(), a -> a.stage())
                .containsExactly(tuple("chris", ActionStage.EXECUTED), tuple("dehren", ActionStage.DENIED));
        assertThat(actions)
                .filteredOn(a -> a.denialReason() == DenialReason.OUTSIDE_WORKSPACE)
                .as("the read aimed outside the worktree")
                .singleElement()
                .satisfies(a -> assertThat(a.summary()).contains("outside.txt"));
        String origin =
                office.query(c -> c.missions().find(m).orElseThrow().origin().name());
        assertThat(origin).isEqualTo(Origin.REAL.name());

        // Agents worked in a worktree, never in the director's checkout.
        assertThat(Files.list(repo).map(p -> p.getFileName().toString()).toList())
                .containsOnly(".git", "README.md");

        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"reason\":\"Inventory delivered and reviewed\"}")
                .post("/api/missions/" + id + "/complete")
                .then()
                .statusCode(200)
                .body("status", equalTo(MissionStatus.COMPLETED.name()));
    }

    @Test
    void aMissionNeedsAGitRepository() throws Exception {
        Path notARepo = Files.createTempDirectory("floor42-plain");

        given().header("Authorization", auth())
                .contentType("application/json")
                .body("{\"title\":\"t\",\"objective\":\"o\",\"repository\":\""
                        + notARepo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_REPOSITORY"));
    }
}
