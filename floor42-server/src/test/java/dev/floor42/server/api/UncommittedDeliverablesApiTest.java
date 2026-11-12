package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCompleted;
import dev.floor42.server.app.DeliverablesHome;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Work left uncommitted in a mission's worktree is said before the director accepts the mission (ADR-0055): the
 * acceptance is refused until they acknowledge exactly what was measured, a git that fails reads as unknown and never
 * as clean, and a workshop change cannot slip between the check and the acceptance.
 */
@QuarkusTest
class UncommittedDeliverablesApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    @Inject
    DeliverablesHome home;

    @Inject
    dev.floor42.server.app.PullRequests pullRequests;

    Question open(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .findFirst()
                .orElse(null));
    }

    /** Approved, worked by the fake developer, validated by the fake reviewer: ready to be accepted. */
    String delivered() throws Exception {
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
        for (int i = 0; i < 2; i++) {
            await("a question for the director", Duration.ofSeconds(20), () -> open(m) != null);
            api().body("{\"answer\":\"ALLOW\"}")
                    .post("/api/questions/" + open(m).id().value() + "/answer")
                    .then()
                    .statusCode(200);
        }
        await(
                "the review",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE);
        return id;
    }

    JsonPath branch(String id) {
        return api().get("/api/missions/" + id + "/deliverables")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    JsonPath uncommitted(String id) {
        return api().get("/api/missions/" + id + "/uncommitted")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    io.restassured.response.Response complete(String id, String acknowledged) {
        String ack = acknowledged == null ? "" : ",\"acknowledged\":\"" + acknowledged + "\"";
        return api().body("{\"reason\":\"Accepted\"" + ack + "}").post("/api/missions/" + id + "/complete");
    }

    MissionStatus status(String id) {
        return office.query(
                c -> c.missions().find(new MissionId(id)).orElseThrow().status());
    }

    List<EventEnvelope> events(String id) {
        return journal.readAll().stream()
                .filter(e -> e.mission() != null && e.mission().value().equals(id))
                .toList();
    }

    @Test
    void anUncommittedDeliverablesFolderIsAcceptedOnlyOnceTheDirectorAcknowledgesIt() throws Exception {
        String id = delivered();
        Path wt = Path.of(branch(id).getString("branches[0].worktree"));
        Files.createDirectories(wt.resolve("deliverables"));
        Files.writeString(wt.resolve("deliverables/report.md"), "# Report\n");
        Files.writeString(wt.resolve("deliverables/summary.md"), "# Summary\n");

        JsonPath seen = uncommitted(id);
        assertThat(seen.getBoolean("clean")).isFalse();
        assertThat(seen.getString("worktrees[0].state")).isEqualTo("DIRTY");
        assertThat(seen.getList("worktrees[0].paths", String.class))
                .contains("deliverables/report.md", "deliverables/summary.md");
        int before = events(id).size();

        complete(id, null).then().statusCode(409).body("code", equalTo("UNCOMMITTED_DELIVERABLES"));
        complete(id, "0000000000000000").then().statusCode(409).body("code", equalTo("UNCOMMITTED_DELIVERABLES"));
        // What the director saw changed before they accepted: they are asked again.
        Files.writeString(wt.resolve("deliverables/late.md"), "# Late\n");
        complete(id, seen.getString("token")).then().statusCode(409);
        assertThat(status(id)).isNotEqualTo(MissionStatus.COMPLETED);
        assertThat(events(id)).as("nothing recorded by a refused acceptance").hasSize(before);

        JsonPath now = uncommitted(id);
        complete(id, now.getString("token")).then().statusCode(200).body("status", equalTo("COMPLETED"));
        List<EventEnvelope> recorded = events(id).subList(before, events(id).size());
        assertThat(recorded)
                .extracting(e -> e.event().getClass().getSimpleName())
                .containsSubsequence("UncommittedWorkAccepted", "MissionCompleted");
        EventEnvelope accepted = recorded.stream()
                .filter(e -> e.event() instanceof UncommittedWorkAccepted)
                .findFirst()
                .orElseThrow();
        assertThat(accepted.actor()).isEqualTo(Actor.director());
        assertThat(accepted.origin()).isEqualTo(Origin.REAL);
        var w = ((UncommittedWorkAccepted) accepted.event()).worktrees().getFirst();
        assertThat(w.state()).isEqualTo("DIRTY");
        assertThat(w.paths()).contains("deliverables/late.md", "deliverables/report.md");
        assertThat(recorded.getLast().event()).isInstanceOf(MissionCompleted.class);
        List<dev.floor42.domain.UncommittedWorktree> kept =
                office.query(c -> c.deliveries().uncommittedAccepted(new MissionId(id)));
        assertThat(kept).hasSize(1);
    }

    @Test
    void aWorktreeGitCannotReadIsUnknownNeverClean() throws Exception {
        String id = delivered();
        Path wt = Path.of(branch(id).getString("branches[0].worktree"));
        // The worktree's index is damaged: git still finds the repository, but "git status" fails there.
        String gitdir = Files.readString(wt.resolve(".git"))
                .strip()
                .substring("gitdir:".length())
                .strip();
        Path index = wt.resolve(gitdir).normalize().resolve("index");
        Files.writeString(index, "not an index");

        JsonPath seen = uncommitted(id);
        assertThat(seen.getBoolean("clean")).isFalse();
        assertThat(seen.getString("worktrees[0].state")).isEqualTo("UNKNOWN");
        assertThat(seen.getString("worktrees[0].reason")).isEqualTo("GIT_FAILED");
        complete(id, null).then().statusCode(409).body("code", equalTo("UNCOMMITTED_DELIVERABLES"));
        // The end-of-mission report says it cannot read the worktree, rather than "nothing uncommitted".
        api().get("/api/missions/" + id + "/report")
                .then()
                .statusCode(200)
                .body("warnings.code", hasItems("WORKTREE_UNREADABLE"));
        // The waits panel says it cannot tell (no count) rather than leaving the line out as "nothing waits".
        String agent = seen.getString("worktrees[0].agent");
        JsonPath progress =
                api().get("/api/progress").then().statusCode(200).extract().jsonPath();
        List<java.util.Map<String, Object>> lines = progress.getList("find { it.mission == '" + id
                + "' }.forYou.findAll { it.kind == 'WORKSHOP' && it.agent == '" + agent + "' }");
        assertThat(lines)
                .singleElement()
                .satisfies(l -> assertThat(l.get("count")).isNull());
        // The pull request draft says -1 (cannot tell), never 0 uncommitted changes.
        assertThat(pullRequests
                        .preview(Actor.director(), new MissionId(id), agent)
                        .pending())
                .isEqualTo(-1);
        complete(id, seen.getString("token")).then().statusCode(200);
    }

    @Test
    void aWorktreeThatLeadsOutOfFloor42sFoldersIsNeverRead() throws Exception {
        String id = delivered();
        Path wt = Path.of(branch(id).getString("branches[0].worktree"));
        Path outside = LiveMissionTest.gitRepository();
        Files.writeString(outside.resolve("loose.md"), "# Not here\n");
        moveAside(wt);
        try {
            Files.createSymbolicLink(wt, outside);
        } catch (Exception e) {
            // Windows without the right to make symbolic links: a junction, which any user may make, leads out as well.
            boolean windows = System.getProperty("os.name")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win");
            Assumptions.assumeTrue(windows, "symbolic links are not available here: " + e.getMessage());
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", wt.toString(), outside.toString())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            Assumptions.assumeTrue(p.waitFor() == 0 && Files.isDirectory(wt), "no junction either");
        }
        JsonPath seen = uncommitted(id);
        assertThat(seen.getString("worktrees[0].state")).isEqualTo("UNKNOWN");
        assertThat(seen.getString("worktrees[0].reason")).isEqualTo("NOT_A_MISSION_WORKTREE");
        assertThat(seen.getList("worktrees[0].paths")).isEmpty();
    }

    @Test
    void aMissingWorktreeIsUnknown() throws Exception {
        String id = delivered();
        moveAside(Path.of(branch(id).getString("branches[0].worktree")));
        JsonPath seen = uncommitted(id);
        assertThat(seen.getString("worktrees[0].state")).isEqualTo("UNKNOWN");
        assertThat(seen.getString("worktrees[0].reason")).isEqualTo("WORKTREE_MISSING");
    }

    /**
     * The close lock (DeliverablesHome.closing) covers the check and the acceptance: a workshop commit that comes in
     * between is refused, so the record never claims a state the worktree no longer has.
     */
    @Test
    void aWorkshopCommitCannotSlipBetweenTheCheckAndTheAcceptance() throws Exception {
        String id = delivered();
        JsonPath b = branch(id);
        Path wt = Path.of(b.getString("branches[0].worktree"));
        String agent = b.getString("branches[0].agent");
        Files.writeString(wt.resolve("notes.md"), "# Notes\n");
        String seen = uncommitted(id).getString("token");

        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        home.afterCheckForTests(() -> {
            checked.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            CompletableFuture<Integer> accepting =
                    CompletableFuture.supplyAsync(() -> complete(id, seen).statusCode());
            assertThat(checked.await(20, TimeUnit.SECONDS)).isTrue();
            api().body("{\"message\":\"Commit the notes\"}")
                    .post("/api/missions/" + id + "/workshop/" + agent + "/accept")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("CLOSING"));
            release.countDown();
            assertThat(accepting.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            home.afterCheckForTests(null);
        }
        var w = office.query(c -> c.deliveries().uncommittedAccepted(new MissionId(id)))
                .getFirst();
        assertThat(w.paths()).contains("notes.md");
        assertThat(Files.exists(wt.resolve("notes.md"))).isTrue();
    }

    private static void moveAside(Path wt) throws Exception {
        Files.move(wt, wt.resolveSibling(wt.getFileName() + "-aside"));
    }
}
