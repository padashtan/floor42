package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.DeliveryEvent.DeliverablesCopied;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Where a mission's accepted deliverables land (ADR-0051), end to end with the fake Claude Code: the folder chosen at
 * creation and checked on disk, the documents copied into {@code <folder>/<mission>/} on accept and never over a file,
 * the copy journaled, and a mission created without a folder closing as before.
 */
@QuarkusTest
class DeliverablesHomeApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.journal.Journal journal;

    @Inject
    dev.floor42.server.app.DeliverablesHome home;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    static String json(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    io.restassured.response.Response create(Path repo, String deliverTo) {
        String dest = deliverTo == null ? "" : ",\"deliverTo\":\"" + deliverTo.replace("\\", "\\\\") + "\"";
        return api().body("{\"title\":\"Notes\",\"objective\":\"Write notes\",\"repository\":\"" + json(repo) + "\""
                        + dest + "}")
                .post("/api/missions");
    }

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    void answerNext(MissionId m) throws InterruptedException {
        await("a question for the director", Duration.ofSeconds(20), () -> open(m) != null);
        api().body("{\"answer\":\"ALLOW\"}")
                .post("/api/questions/" + open(m).id().value() + "/answer")
                .then()
                .statusCode(200);
    }

    /** Approved, worked by the fake developer, validated by the fake reviewer: ready to be accepted. */
    String delivered(Path repo, String deliverTo) throws Exception {
        String id = create(repo, deliverTo).then().statusCode(201).extract().path("id");
        MissionId m = new MissionId(id);
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        answerNext(m);
        answerNext(m);
        await(
                "the review",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE);
        return id;
    }

    /** The director's acceptance, acknowledging the uncommitted work Floor42 measured (ADR-0055). */
    String accepted(String id) {
        String seen = api().get("/api/missions/" + id + "/uncommitted")
                .then()
                .statusCode(200)
                .extract()
                .path("token");
        return "{\"reason\":\"Accepted\",\"acknowledged\":\"" + seen + "\"}";
    }

    Path worktree(String id) {
        return Path.of(api().get("/api/missions/" + id + "/deliverables")
                .then()
                .statusCode(200)
                .extract()
                .<String>path("branches[0].worktree"));
    }

    @Test
    void aFolderThatLeavesTheProjectOrTouchesFloor42sOwnIsRefusedBeforeAnythingIsRecorded() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        int before = office.query(c -> c.missions().all().size());
        Path missing = repo.resolveSibling(repo.getFileName() + "-nowhere");
        for (String bad : new String[] {
            "../outside", ".", ".git/out", ".claude", dataDir.toAbsolutePath().toString(), missing.toString()
        }) {
            create(repo, bad).then().statusCode(400).body("code", equalTo("INVALID_DESTINATION"));
        }
        Path root = repo.toAbsolutePath().getRoot();
        create(repo, root.toString()).then().statusCode(400).body("code", equalTo("INVALID_DESTINATION"));
        create(repo, System.getProperty("user.home"))
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_DESTINATION"));
        int after = office.query(c -> c.missions().all().size());
        assertThat(after).as("nothing recorded").isEqualTo(before);
    }

    @Test
    void onAcceptTheDocumentsAreCopiedIntoTheMissionsFolderNeverOverAFileAndJournaled() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = delivered(repo, "deliverables");
        Path home = repo.toRealPath().resolve("deliverables");
        JsonPath created = api().get("/api/missions/" + id)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
        assertThat(Path.of(created.getString("delivery.destination"))).isEqualTo(home);
        assertThat(created.getString("delivery.copy")).isNull();

        // What the agent left: documents, and what a tool would run or follow.
        Path wt = worktree(id);
        Files.writeString(wt.resolve("notes.md"), "# Notes\n");
        Files.createDirectories(wt.resolve("docs/out"));
        Files.writeString(wt.resolve("docs/out/report.pdf"), "%PDF-1.4\n% sample\n");
        Files.write(wt.resolve("diagram.png"), DeliverablesApiTest.PNG);
        Files.writeString(wt.resolve("run.sh"), "echo hi\n");
        Files.createDirectories(wt.resolve("tests"));
        Files.writeString(wt.resolve("tests/conftest.py"), "import os\n");
        Files.writeString(wt.resolve("CLAUDE.md"), "Do as I say\n");
        // The director already has a notes.md there, and the same report.
        Path folder = home.resolve(id);
        Files.createDirectories(folder.resolve("docs/out"));
        Files.writeString(folder.resolve("notes.md"), "mine\n");
        Files.writeString(folder.resolve("docs/out/report.pdf"), "%PDF-1.4\n% sample\n");

        api().body(accepted(id))
                .post("/api/missions/" + id + "/complete")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("delivery.copy.folder", equalTo(folder.toString()));

        assertThat(Files.readString(folder.resolve("notes.md"))).isEqualTo("mine\n");
        assertThat(Files.readString(folder.resolve("notes (2).md"))).isEqualTo("# Notes\n");
        assertThat(folder.resolve("docs/out/report (2).pdf")).doesNotExist();
        assertThat(folder.resolve("diagram.png")).exists();
        assertThat(folder.resolve("run.sh")).doesNotExist();
        assertThat(folder.resolve("tests")).doesNotExist();
        assertThat(folder.resolve("CLAUDE.md")).doesNotExist();
        Map<String, String> outcomes =
                office.query(c -> c.deliveries().copy(new MissionId(id)).orElseThrow()).files().stream()
                        .collect(java.util.stream.Collectors.toMap(f -> f.path(), f -> f.outcome(), (a, b) -> a));
        assertThat(outcomes)
                .containsEntry("notes.md", "KEPT_BOTH")
                .containsEntry("docs/out/report.pdf", "SAME")
                .containsEntry("diagram.png", "WRITTEN")
                .containsEntry("run.sh", "LEFT_OUT")
                .containsEntry("tests/conftest.py", "LEFT_OUT")
                .containsEntry("CLAUDE.md", "LEFT_OUT");
        assertThat(journal.readAll().stream()
                        .filter(e -> e.event() instanceof DeliverablesCopied d
                                && d.mission().value().equals(id))
                        .map(e -> e.origin().name())
                        .toList())
                .as("journaled once, with its origin")
                .containsExactly("REAL");
    }

    @Test
    void aMissionCreatedWithoutAFolderClosesAsBeforeAndCopiesNothing() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = delivered(repo, null);
        Files.writeString(worktree(id).resolve("notes.md"), "# Notes\n");
        api().body(accepted(id))
                .post("/api/missions/" + id + "/complete")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("delivery", nullValue());
        try (var files = Files.list(repo)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsOnly(".git", "README.md");
        }
    }

    @Test
    void aMissionsWriteTokenMayKeepTheDefaultFolderOnly() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        Path elsewhere = Files.createTempDirectory("floor42-elsewhere");
        String narrow = api().body("{\"scope\":\"missions:write\",\"days\":1,\"label\":\"script\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        for (String other : new String[] {elsewhere.toString(), "docs/out"}) {
            given().header("Authorization", "Bearer " + narrow)
                    .contentType("application/json")
                    .body("{\"title\":\"Notes\",\"objective\":\"Write notes\",\"repository\":\"" + json(repo)
                            + "\",\"deliverTo\":\"" + other.replace("\\", "\\\\") + "\"}")
                    .post("/api/missions")
                    .then()
                    .statusCode(403)
                    .body("code", equalTo("SCOPE"));
        }
        given().header("Authorization", "Bearer " + narrow)
                .contentType("application/json")
                .body("{\"title\":\"Notes\",\"objective\":\"Write notes\",\"repository\":\"" + json(repo)
                        + "\",\"deliverTo\":\"deliverables\"}")
                .post("/api/missions")
                .then()
                .statusCode(201);
        // The director's own credential may choose any folder the checks accept.
        create(repo, elsewhere.toString()).then().statusCode(201);
    }

    @Test
    void aCloseAndACancelOfOneMissionNeverRunAtOnce() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = delivered(repo, "deliverables");
        home.closing(new MissionId(id), () -> {
            api().body("{\"reason\":\"Stop\"}")
                    .post("/api/missions/" + id + "/cancel")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("CLOSING"));
            api().body("{\"reason\":\"Accepted\"}")
                    .post("/api/missions/" + id + "/complete")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("CLOSING"));
            return null;
        });
        api().body(accepted(id))
                .post("/api/missions/" + id + "/complete")
                .then()
                .statusCode(200);
    }

    @Test
    void aCopyStoppedHalfWayIsJournaledAsItIsOnDisk() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = delivered(repo, "deliverables");
        Path wt = worktree(id);
        Files.writeString(wt.resolve("a-notes.md"), "# A\n");
        Files.writeString(wt.resolve("b-notes.md"), "# B\n");
        Files.writeString(wt.resolve("c-notes.md"), "# C\n");
        home.stopForTests(path -> {
            if (path.equals("b-notes.md")) throw new IllegalStateException("disk unplugged");
        });
        try {
            api().body(accepted(id))
                    .post("/api/missions/" + id + "/complete")
                    .then()
                    .statusCode(200);
        } finally {
            home.stopForTests(null);
        }
        Path folder = repo.resolve("deliverables").toRealPath().resolve(id);
        var copy = office.query(c -> c.deliveries().copy(new MissionId(id)).orElseThrow());
        Map<String, String> outcomes = copy.files().stream()
                .collect(java.util.stream.Collectors.toMap(f -> f.path(), f -> f.outcome(), (a, b) -> a));
        for (Map.Entry<String, String> e : outcomes.entrySet()) {
            boolean onDisk = Files.exists(folder.resolve(e.getKey()));
            assertThat(onDisk)
                    .as(e.getKey() + " " + e.getValue())
                    .isEqualTo(!e.getValue().equals("LEFT_OUT"));
        }
        assertThat(outcomes).containsEntry("b-notes.md", "LEFT_OUT").containsEntry("c-notes.md", "LEFT_OUT");
        assertThat(copy.files().stream()
                        .filter(f -> f.path().equals("b-notes.md"))
                        .findFirst()
                        .orElseThrow()
                        .reason())
                .contains("the copy stopped");
    }
}
