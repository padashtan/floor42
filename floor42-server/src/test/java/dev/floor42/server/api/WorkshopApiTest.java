package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.api.WorkshopResource.WorkshopView;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.app.MissionScheduler;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SchedulerQuiet;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The director's workshop end to end (spec section 11): a real git worktree, the fake Claude Code, and the director
 * editing, directing, accepting and rejecting through the API.
 */
@QuarkusTest
class WorkshopApiTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    @Inject
    MissionScheduler scheduler;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    String auth() {
        return "Bearer " + token.value();
    }

    io.restassured.specification.RequestSpecification api() {
        return given().header("Authorization", auth()).contentType("application/json");
    }

    void answerNext(MissionId m, String answer) throws InterruptedException {
        await("a question for the director", Duration.ofSeconds(20), () -> open(m) != null);
        api().body("{\"answer\":\"" + answer + "\"}")
                .post("/api/questions/" + open(m).id().value() + "/answer")
                .then()
                .statusCode(200);
    }

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    Ticket ticket(MissionId m) {
        return office.query(c -> c.tickets().of(m).getFirst());
    }

    WorkshopView workshop(String id) {
        return api().get("/api/missions/" + id + "/workshop")
                .then()
                .statusCode(200)
                .extract()
                .as(WorkshopView.class);
    }

    /**
     * A real run answered an accept with a 409 once, then 200 on a second try, with no visible cause: a session was
     * still open on the mission. The refusal now names whose session and on which ticket.
     */
    @Test
    void anAcceptWhileASessionRunsSaysWhoseSession() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Busy\",\"objective\":\"Write notes [fake:hang]\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
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
        await(
                "the producer at work",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.sessions().of(m).stream().anyMatch(dev.floor42.domain.AgentSession::isOpen)));
        String producer =
                office.query(c -> c.sessions().of(m).getFirst().agent().value());
        String t = ticket(m).id().value();
        await(
                "the workspace",
                Duration.ofSeconds(10),
                () -> !workshop(id).workspaces().isEmpty());
        Path worktree = Path.of(workshop(id).workspaces().getFirst().path());
        Files.writeString(worktree.resolve("pending.txt"), "the director's change\n");
        String head = LiveMissionTest.output(worktree, "git", "rev-parse", "HEAD");

        api().body("{\"message\":\"Too early\"}")
                .post("/api/missions/" + id + "/workshop/" + producer + "/accept")
                .then()
                .statusCode(409)
                .body("code", equalTo("AGENT_AT_WORK"))
                .body("message", containsString(producer + " on " + t));
        // Refused before anything is staged or committed: the session at work finds the worktree as it was.
        assertThat(LiveMissionTest.output(worktree, "git", "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(LiveMissionTest.output(worktree, "git", "diff", "--cached", "--name-only"))
                .isEmpty();
        assertThat(worktree.resolve("pending.txt")).exists();

        api().body("{\"reason\":\"test over\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
    }

    @Test
    void theDirectorEditsDirectsAcceptsAndRejects() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Notes\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
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
        answerNext(m, "ALLOW"); // git status
        answerNext(m, "ALLOW"); // the first write
        await("the first review", Duration.ofSeconds(30), () -> ticket(m).status() == TicketStatus.DONE);

        // The workspace is recorded with its branch and base.
        WorkshopView view = workshop(id);
        assertThat(view.workspaces()).singleElement().satisfies(w -> {
            assertThat(w.agent()).isEqualTo("chris");
            assertThat(w.branch()).isEqualTo("floor42/" + id.toLowerCase() + "-chris");
            assertThat(w.head()).isEqualTo(w.baseCommit());
        });
        Path worktree = Path.of(view.workspaces().getFirst().path());

        // The director edits a file: Floor42 writes it, measures the diff, records the intervention.
        api().body("{\"content\":\"director's notes\\n\",\"note\":\"start the notes\"}")
                .put("/api/missions/" + id + "/workshop/chris/file?path=notes.txt")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes[0].path", equalTo("notes.txt"))
                .body("workspaces[0].changes[0].status", equalTo("ADDED"))
                .body("workspaces[0].editedByDirector[0]", equalTo("notes.txt"))
                .body("interventions[0].kind", equalTo("DIRECTOR_EDIT"));
        api().get("/api/missions/" + id + "/workshop/chris/diff?path=notes.txt")
                .then()
                .statusCode(200)
                .body("diff", containsString("+director's notes"));
        api().get("/api/missions/" + id + "/workshop/chris/file?path=README.md")
                .then()
                .statusCode(200)
                .body("content", org.hamcrest.Matchers.startsWith("# Fake project"));

        // The workshop is confined like the agents are.
        api().get("/api/missions/" + id + "/workshop/chris/file?path=../../secret.txt")
                .then()
                .statusCode(400)
                .body("code", equalTo("OUTSIDE_WORKSPACE"));
        api().body("{\"content\":\"[core]\"}")
                .put("/api/missions/" + id + "/workshop/chris/file?path=.git")
                .then()
                .statusCode(400)
                .body("code", equalTo("OUTSIDE_WORKSPACE"));

        // A directive sends the work back; the producer's write to the director's file must be asked again.
        String t = ticket(m).id().value();
        api().body("{\"ticket\":\"" + t + "\",\"directive\":\"Keep the notes short\"}")
                .post("/api/missions/" + id + "/workshop/directive")
                .then()
                .statusCode(200);
        String brief = office.query(c -> Briefs.producer(
                c,
                c.missions().find(m).orElseThrow(),
                c.tickets().of(m).getFirst(),
                c.staff().find(new AgentId("chris")).orElseThrow()));
        assertThat(brief).contains("Keep the notes short").contains("- notes.txt");
        await("the protected write", Duration.ofSeconds(20), () -> open(m) != null);
        assertThat(open(m).context()).contains("you edited notes.txt");
        answerNext(m, "DENY");
        await(
                "the second review",
                Duration.ofSeconds(30),
                () -> ticket(m).status() == TicketStatus.DONE
                        && ticket(m).reviews().size() == 2);
        // The ticket reads DONE while its review task still measures the worktree (ls-files, diff, status): a git
        // status there holds the index lock for a moment, and the test's own commit below then stops on "index.lock:
        // File exists" (exit 128). The test touches the index only once the daemon's work on the ticket has ended.
        SchedulerQuiet.await(scheduler);
        assertThat(Files.readString(worktree.resolve("notes.txt"))).isEqualTo("director's notes\n");

        // Caches a run left behind are no delivery: listed apart and never committed, unless the repository tracks
        // files of the kind already (here node_modules, so a new one there is committed as any other file).
        Files.createDirectories(worktree.resolve("lib/node_modules"));
        Files.writeString(worktree.resolve("lib/node_modules/kept.js"), "kept\n");
        LiveMissionTest.output(worktree, "git", "add", "-f", "lib/node_modules/kept.js");
        LiveMissionTest.output(
                worktree, "git", "-c", "user.name=t", "-c", "user.email=t@t.invalid", "commit", "-q", "-m", "vendored");
        Files.createDirectories(worktree.resolve("__pycache__"));
        Files.write(worktree.resolve("__pycache__/notes.cpython-312.pyc"), new byte[] {0x55, 0x0d, 0x0d, 0x0a, 0});
        Files.createDirectories(worktree.resolve("tools/.pytest_cache"));
        Files.writeString(worktree.resolve("tools/.pytest_cache/README.md"), "cache\n");
        Files.createDirectories(worktree.resolve("node_modules/x"));
        Files.writeString(worktree.resolve("node_modules/x/index.js"), "meant\n");
        api().get("/api/missions/" + id + "/workshop")
                .then()
                .statusCode(200)
                .body(
                        "workspaces[0].leftOut",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "__pycache__/notes.cpython-312.pyc", "tools/.pytest_cache/README.md"))
                .body(
                        "workspaces[0].changes.path",
                        org.hamcrest.Matchers.hasItems("notes.txt", "node_modules/x/index.js"))
                .body(
                        "workspaces[0].changes.path",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("__pycache__/notes.cpython-312.pyc")));
        // Without a path, the whole pending diff, bounded: a file too large is counted as omitted, not read; the
        // artefacts left out are not in it.
        Files.writeString(worktree.resolve("huge.txt"), "x".repeat(512 * 1024 + 1));
        api().get("/api/missions/" + id + "/workshop/chris/diff")
                .then()
                .statusCode(200)
                .body("omitted", equalTo(1))
                .body("diff", org.hamcrest.Matchers.not(containsString("xxxxxxxx")))
                .body("diff", containsString("+director's notes"));
        Files.delete(worktree.resolve("huge.txt"));
        api().get("/api/missions/" + id + "/workshop/chris/diff")
                .then()
                .statusCode(200)
                .body("omitted", equalTo(0))
                .body("diff", containsString("+director's notes"))
                .body("diff", containsString("+meant"))
                .body("diff", org.hamcrest.Matchers.not(containsString("pycache")));

        // Accepting commits on the agent's branch, never in the director's checkout.
        api().body("{\"message\":\"Add the director's notes\"}")
                .post("/api/missions/" + id + "/workshop/chris/accept")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.size()", equalTo(0))
                .body("workspaces[0].leftOut.size()", equalTo(2))
                .body("interventions[2].kind", equalTo("PATCH_ACCEPTED"));
        assertThat(LiveMissionTest.output(worktree, "git", "log", "-1", "--format=%s"))
                .isEqualTo("Add the director's notes");
        assertThat(LiveMissionTest.output(worktree, "git", "show", "--name-only", "--format=", "HEAD")
                        .lines()
                        .toList())
                .containsExactlyInAnyOrder("notes.txt", "node_modules/x/index.js");
        assertThat(LiveMissionTest.output(repo, "git", "rev-list", "--count", "main"))
                .isEqualTo("1");
        assertThat(repo.resolve("notes.txt")).doesNotExist();

        // Rejecting archives the changes, then restores the workspace.
        api().body("{\"content\":\"wrong\\n\"}")
                .put("/api/missions/" + id + "/workshop/chris/file?path=extra.txt")
                .then()
                .statusCode(200);
        String archive = api().body("{\"message\":\"Not needed\"}")
                .post("/api/missions/" + id + "/workshop/chris/reject")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.size()", equalTo(0))
                .extract()
                .path("interventions[4].detail");
        assertThat(worktree.resolve("extra.txt")).doesNotExist();
        assertThat(Files.readString(dataDir.resolve(archive), StandardCharsets.UTF_8))
                .contains("+wrong");
    }
}
