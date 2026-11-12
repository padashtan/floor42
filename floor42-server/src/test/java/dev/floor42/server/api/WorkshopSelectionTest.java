package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.Workspace;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.WorkshopDiscard;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The director ticks the files an accept commits, and can give up one change; the rest stays in the worktree. */
@QuarkusTest
class WorkshopSelectionTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.runtime.Git git;

    @Inject
    dev.floor42.server.app.Workshop workshop;

    @Inject
    dev.floor42.server.app.AgentReservations reservations;

    record Ready(String id, String producer, Path worktree, String head) {}

    /** A reviewed, idle mission with a new file, a second new file and a modified README waiting in its worktree. */
    Ready ready() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Pick\",\"objective\":\"Write notes\",\"repository\":\""
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
        await("the reviewed ticket", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE
                    && office.query(c -> c.sessions().of(m).stream().noneMatch(s -> s.isOpen()));
        });
        Workspace w = office.query(c -> c.workshop().workspaces(m).getFirst());
        Path worktree = Path.of(w.path());
        Files.writeString(worktree.resolve("one.txt"), "one\n");
        Files.writeString(worktree.resolve("two.txt"), "two\n");
        Files.writeString(worktree.resolve("README.md"), "changed\n");
        return new Ready(id, w.agent().value(), worktree, WorkshopHoldTest.out(worktree, "rev-parse", "HEAD"));
    }

    Response accept(Ready r, String paths) {
        return api().body("{\"message\":\"Pick\",\"paths\":" + paths + "}")
                .post("/api/missions/" + r.id() + "/workshop/" + r.producer() + "/accept");
    }

    @Test
    void onlyTheTickedFilesAreCommittedTheOthersStayPending() throws Exception {
        Ready r = ready();
        accept(r, "[\"one.txt\"]")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.path", org.hamcrest.Matchers.hasItems("two.txt", "README.md"))
                .body("workspaces[0].changes.size()", equalTo(2));
        assertThat(WorkshopHoldTest.out(r.worktree(), "show", "--name-only", "--format=", "HEAD"))
                .isEqualTo("one.txt");
        assertThat(WorkshopHoldTest.out(r.worktree(), "diff", "--cached", "--name-only"))
                .isEmpty();
        assertThat(r.worktree().resolve("two.txt")).exists();
        assertThat(Files.readString(r.worktree().resolve("README.md"))).isEqualTo("changed\n");
        api().get("/api/missions/" + r.id() + "/workshop")
                .then()
                .body("interventions[-1].kind", equalTo("PATCH_ACCEPTED"))
                .body("interventions[-1].files", org.hamcrest.Matchers.contains("one.txt"));
    }

    @Test
    void aPathThatIsNotPendingOrLiesOutsideIsRefusedAndNothingIsCommitted() throws Exception {
        Ready r = ready();
        accept(r, "[\"one.txt\",\"never-changed.txt\"]").then().statusCode(409).body("code", equalTo("NOT_PENDING"));
        accept(r, "[\"one.txt\",\"../../secret.txt\"]")
                .then()
                .statusCode(400)
                .body("code", equalTo("OUTSIDE_WORKSPACE"));
        accept(r, "[]").then().statusCode(409).body("code", equalTo("EMPTY_PATCH"));
        assertThat(WorkshopHoldTest.out(r.worktree(), "rev-parse", "HEAD")).isEqualTo(r.head());
        assertThat(WorkshopHoldTest.out(r.worktree(), "diff", "--cached", "--name-only"))
                .isEmpty();
    }

    @Test
    void theDiffOfOneHugeFileIsRefusedAndAFileThatIsNotUtf8IsNotEditable() throws Exception {
        Ready r = ready();
        String base = "/api/missions/" + r.id() + "/workshop/" + r.producer();
        Files.writeString(r.worktree().resolve("big.txt"), "x".repeat(600 * 1024));
        api().get(base + "/diff?path=big.txt").then().statusCode(413).body("code", equalTo("FILE_TOO_LARGE"));
        Files.write(r.worktree().resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n'});
        api().get(base + "/file?path=latin1.txt").then().statusCode(200).body("binary", equalTo(true));
        api().get(base + "/file?path=one.txt").then().statusCode(200).body("binary", equalTo(false));
    }

    /** A file the branch tracks, large: then deleted or shrunk, git still prints all of it in a diff against HEAD. */
    @Test
    void theDiffOfATrackedLargeFileIsBoundedWhateverItsWorkingCopyIs() throws Exception {
        Ready r = ready();
        String base = "/api/missions/" + r.id() + "/workshop/" + r.producer();
        Files.writeString(r.worktree().resolve("big.txt"), ("x".repeat(99) + "\n").repeat(6_000));
        WorkshopHoldTest.out(r.worktree(), "add", "big.txt");
        WorkshopHoldTest.out(
                r.worktree(), "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "big", "--", "big.txt");

        Files.delete(r.worktree().resolve("big.txt"));
        api().get(base + "/diff?path=big.txt").then().statusCode(413).body("code", equalTo("FILE_TOO_LARGE"));
        Files.writeString(r.worktree().resolve("big.txt"), "small\n");
        api().get(base + "/diff?path=big.txt").then().statusCode(413).body("code", equalTo("FILE_TOO_LARGE"));
    }

    @Test
    void savingOverAFileThatIsNotUtf8IsRefusedAndAnExistingCrlfIsKept() throws Exception {
        Ready r = ready();
        String base = "/api/missions/" + r.id() + "/workshop/" + r.producer();
        Files.write(r.worktree().resolve("latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n'});
        api().body("{\"content\":\"hello\"}")
                .put(base + "/file?path=latin1.txt")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_TEXT"));
        assertThat(Files.readAllBytes(r.worktree().resolve("latin1.txt"))).hasSize(5);

        Files.writeString(r.worktree().resolve("crlf.txt"), "one\r\ntwo\r\n");
        api().body("{\"content\":\"one\\ntwo\\nthree\\n\"}")
                .put(base + "/file?path=crlf.txt")
                .then()
                .statusCode(200);
        assertThat(Files.readString(r.worktree().resolve("crlf.txt"))).isEqualTo("one\r\ntwo\r\nthree\r\n");
    }

    @Test
    void withoutPathsEveryPendingChangeIsCommitted() throws Exception {
        Ready r = ready();
        api().body("{\"message\":\"All\"}")
                .post("/api/missions/" + r.id() + "/workshop/" + r.producer() + "/accept")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.size()", equalTo(0));
    }

    @Test
    void discardingRestoresATrackedFileDeletesANewOneAndIsJournaled() throws Exception {
        Ready r = ready();
        String base = "/api/missions/" + r.id() + "/workshop/" + r.producer() + "/discard?path=";
        api().post(base + "README.md").then().statusCode(200).body("workspaces[0].changes.size()", equalTo(2));
        assertThat(Files.readString(r.worktree().resolve("README.md"))).startsWith("# Fake project");
        api().post(base + "two.txt")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.path", org.hamcrest.Matchers.contains("one.txt"))
                .body("interventions[-1].kind", equalTo("DIRECTOR_EDIT"))
                .body("interventions[-1].detail", org.hamcrest.Matchers.containsString("two"));
        assertThat(r.worktree().resolve("two.txt")).doesNotExist();
        assertThat(r.worktree().resolve("one.txt")).exists();
        api().post(base + "two.txt").then().statusCode(409).body("code", equalTo("NOT_PENDING"));
        api().post(base + "../../x").then().statusCode(400).body("code", equalTo("OUTSIDE_WORKSPACE"));
    }

    /** A staged rename (git knows it as R): the new name is the pending change, the old one goes with it. */
    Ready renamed() throws Exception {
        Ready r = ready();
        // The daemon's own git reads of the worktree may hold its index.lock for a moment: retried, not a product
        // failure.
        await("the staged rename", Duration.ofSeconds(20), () -> {
            try {
                WorkshopHoldTest.out(r.worktree(), "checkout", "--quiet", "README.md");
                WorkshopHoldTest.out(r.worktree(), "mv", "README.md", "MOVED.md");
                return true;
            } catch (Exception | AssertionError e) {
                // Handled: retried until the rename is staged or the wait gives up
                return java.nio.file.Files.exists(r.worktree().resolve("MOVED.md"));
            }
        });
        return r;
    }

    @Test
    void aRenamedFileIsCommittedWithItsFormerName() throws Exception {
        Ready r = renamed();
        accept(r, "[\"MOVED.md\"]")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.path", org.hamcrest.Matchers.hasItems("one.txt", "two.txt"));
        // Git reads the commit as one rename: both the old and the new name went in.
        assertThat(WorkshopHoldTest.out(r.worktree(), "show", "--name-status", "--format=", "HEAD"))
                .matches("R\\d*\\s+README.md\\s+MOVED.md");
    }

    @Test
    void discardingARenamedFileUndoesBothSides() throws Exception {
        Ready r = renamed();
        api().post("/api/missions/" + r.id() + "/workshop/" + r.producer() + "/discard?path=MOVED.md")
                .then()
                .statusCode(200)
                .body("workspaces[0].changes.path", org.hamcrest.Matchers.contains("one.txt", "two.txt"));
        assertThat(r.worktree().resolve("MOVED.md")).doesNotExist();
        assertThat(Files.readString(r.worktree().resolve("README.md"))).startsWith("# Fake project");
    }

    @Test
    void aMissingPathIsAnInvalidRequestNotAServerError() throws Exception {
        Ready r = ready();
        api().post("/api/missions/" + r.id() + "/workshop/" + r.producer() + "/discard")
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"));
        accept(r, "[null]").then().statusCode(400).body("code", equalTo("INVALID_ARGUMENT"));
        assertThat(WorkshopHoldTest.out(r.worktree(), "rev-parse", "HEAD")).isEqualTo(r.head());
    }

    @Test
    void discardingWhileTheAgentIsHeldIsRefusedAndTouchesNothing() throws Exception {
        Ready r = ready();
        var held = reservations
                .reserve(new dev.floor42.domain.AgentId(r.producer()))
                .orElseThrow();
        try {
            api().post("/api/missions/" + r.id() + "/workshop/" + r.producer() + "/discard?path=two.txt")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("AGENT_AT_WORK"));
        } finally {
            reservations.release(held);
        }
        assertThat(r.worktree().resolve("two.txt")).exists();
    }

    @Test
    void aRestoreThatFailsIsJournaledAndAnswered() throws Exception {
        Ready r = ready();
        QuarkusMock.installMockForType(
                new WorkshopDiscard(office, git, workshop, reservations) {
                    @Override
                    protected void restore(Path root, String side) {
                        throw new IllegalStateException("the file is locked");
                    }
                },
                WorkshopDiscard.class);
        api().post("/api/missions/" + r.id() + "/workshop/" + r.producer() + "/discard?path=two.txt")
                .then()
                .statusCode(409)
                .body("code", equalTo("DISCARD_FAILED"));
        assertThat(r.worktree().resolve("two.txt")).exists();
        api().get("/api/missions/" + r.id() + "/workshop")
                .then()
                .body("interventions[-2].subject", org.hamcrest.Matchers.containsString("Discarded this change"))
                .body("interventions[-1].subject", org.hamcrest.Matchers.containsString("Discard failed"))
                .body("interventions[-1].subject", org.hamcrest.Matchers.containsString("the file is locked"));
    }
}
