package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The director gives a mission files: written into the project, committed on the director's branch. */
@QuarkusTest
class DirectorFilesApiTest extends ApiTestBase {

    @Inject
    dev.floor42.server.app.OfficeService office;

    @Inject
    dev.floor42.server.app.Workspaces workspaces;

    @TempDir
    Path dir;

    String mission(Path repo) {
        return api().body("{\"title\": \"Report\", \"objective\": \"Write a report\", \"repository\": \""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    static String git(Path repo, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(java.util.List.of("git", "-C", repo.toString()));
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
        return out;
    }

    static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void filesGivenAreCommittedOnTheDirectorsBranchAndCopiedFilesToo() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("report"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "-c", "user.name=T", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "start");
        String m = mission(repo);

        api().body("{\"folder\": \"inputs\", \"files\": [{\"name\": \"notes.txt\", \"content\": \""
                        + base64("Meeting notes") + "\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(200)
                .body("branch", equalTo("main"))
                .body("pendingCount", equalTo(0));
        assertThat(Files.readString(repo.resolve("inputs/notes.txt"))).isEqualTo("Meeting notes");
        assertThat(git(repo, "log", "-1", "--format=%s"))
                .startsWith("Director's files for " + m + ": inputs/notes.txt");

        // The same name again is refused: nothing is overwritten.
        api().body("{\"folder\": \"inputs\", \"files\": [{\"name\": \"notes.txt\", \"content\": \"\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(409)
                .body("code", equalTo("FILES_REFUSED"));
        // Nor into git's own files, nor out of the project: the request itself is wrong.
        api().body("{\"folder\": \".git/hooks\", \"files\": [{\"name\": \"pre-commit\", \"content\": \"\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(400)
                .body("code", equalTo("FILES_REFUSED"));
        api().body("{\"folder\": \"../elsewhere\", \"files\": [{\"name\": \"x.txt\", \"content\": \"\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(400);

        // A file the director copied into the folder, and a stray secret next to it: both pending, and only the
        // confirmed one is committed.
        Files.writeString(repo.resolve("photo-notes.md"), "notes");
        Files.writeString(repo.resolve(".env"), "TOKEN=secret");
        api().get("/api/missions/" + m + "/files").then().body("pending", containsInAnyOrder("photo-notes.md", ".env"));
        api().body("{}").post("/api/missions/" + m + "/files/commit").then().statusCode(400);
        api().body("{\"paths\": [\"photo-notes.md\", \"elsewhere.md\"]}")
                .post("/api/missions/" + m + "/files/commit")
                .then()
                .statusCode(409)
                .body("message", containsString("elsewhere.md"));
        api().body("{\"paths\": [\"photo-notes.md\"]}")
                .post("/api/missions/" + m + "/files/commit")
                .then()
                .statusCode(200)
                .body("pending", contains(".env"));
        assertThat(git(repo, "log", "-1", "--format=%s")).startsWith("Director's changes for " + m);
        assertThat(git(repo, "show", "--name-only", "--format=", "HEAD").strip())
                .isEqualTo("photo-notes.md");
        Files.delete(repo.resolve(".env"));
        api().body("{\"paths\": [\"photo-notes.md\"]}")
                .post("/api/missions/" + m + "/files/commit")
                .then()
                .statusCode(409)
                .body("code", equalTo("FILES_REFUSED"));

        // No agent has a worktree yet: nothing to update.
        api().post("/api/missions/" + m + "/files/update-agents")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    void updatingTheAgentsMergesTheDirectorsBranchAndAConflictLosesNothing() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("site"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "start\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=T", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
        String m = mission(repo);
        var mission = new dev.floor42.domain.MissionId(m);
        var agent = office.query(c -> c.staff().all().iterator().next().id());
        Path worktree = workspaces.worktree(repo.toString(), mission, agent);
        String base = workspaces.head(worktree);
        office.execute(c -> c.workshop()
                .prepareWorkspace(
                        office.context(dev.floor42.domain.Actor.system(), dev.floor42.domain.Origin.REAL),
                        mission,
                        agent,
                        worktree.toString(),
                        workspaces.branch(worktree),
                        base));

        api().body("{\"files\": [{\"name\": \"notes.txt\", \"content\": \"" + base64("Meeting notes") + "\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .body("agents[0].upToDate", equalTo(false));
        api().post("/api/missions/" + m + "/files/update-agents")
                .then()
                .statusCode(200)
                .body("[0].clean", equalTo(true))
                .body("[0].skipped", equalTo(false));
        assertThat(Files.readString(worktree.resolve("notes.txt"))).isEqualTo("Meeting notes");
        api().get("/api/missions/" + m + "/files").then().body("agents[0].upToDate", equalTo(true));
        api().post("/api/missions/" + m + "/files/update-agents").then().body("[0].skipped", equalTo(true));

        // The agent and the director both change README.md: the merge is undone, the agent's commit stays.
        Files.writeString(worktree.resolve("README.md"), "agent\n");
        git(worktree, "-c", "user.name=A", "-c", "user.email=a@a", "commit", "-q", "-am", "agent's work");
        String agentHead = workspaces.head(worktree);
        Files.writeString(repo.resolve("README.md"), "director\n");
        api().body("{\"paths\": [\"README.md\"]}")
                .post("/api/missions/" + m + "/files/commit")
                .then()
                .statusCode(200);
        api().post("/api/missions/" + m + "/files/update-agents")
                .then()
                .body("[0].clean", equalTo(false))
                .body("[0].detail", org.hamcrest.Matchers.containsString("README.md"));
        assertThat(workspaces.head(worktree)).isEqualTo(agentHead);
        assertThat(Files.readString(worktree.resolve("README.md")).strip()).isEqualTo("agent");
    }

    @Test
    void aWrongGiftIsABadRequestAndATooLargeOneSaysSo() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("gift"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "-c", "user.name=T", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "start");
        String m = mission(repo);

        api().body("{\"files\": [{\"name\": \"\", \"content\": \"\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(400)
                .body("code", equalTo("FILES_REFUSED"));
        api().body("{\"files\": [{\"name\": \"a.txt\", \"content\": \"not base64!\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(400)
                .body("message", containsString("base64"));
        StringBuilder many = new StringBuilder("{\"files\": [");
        for (int i = 0; i <= dev.floor42.server.app.DirectorFiles.MAX_FILES; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\": \"f").append(i).append(".txt\", \"content\": \"\"}");
        }
        api().body(many.append("]}").toString())
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(413)
                .body("code", equalTo("FILES_REFUSED"));
        // An unknown mission is not found, in the same {code, message} shape.
        api().get("/api/missions/M-99999/files")
                .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", containsString("M-99999"));
    }

    @Test
    void aMergeTheJournalWouldRefuseIsNeverMade() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("closed"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "start\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.name=T", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
        String m = mission(repo);
        var mission = new dev.floor42.domain.MissionId(m);
        var agent = office.query(c -> c.staff().all().iterator().next().id());
        Path worktree = workspaces.worktree(repo.toString(), mission, agent);
        String base = workspaces.head(worktree);
        office.execute(c -> c.workshop()
                .prepareWorkspace(
                        office.context(dev.floor42.domain.Actor.system(), dev.floor42.domain.Origin.REAL),
                        mission,
                        agent,
                        worktree.toString(),
                        workspaces.branch(worktree),
                        base));
        api().body("{\"files\": [{\"name\": \"notes.txt\", \"content\": \"" + base64("Meeting notes") + "\"}]}")
                .post("/api/missions/" + m + "/files")
                .then()
                .statusCode(200);
        api().body("{\"reason\": \"over\"}")
                .post("/api/missions/" + m + "/cancel")
                .then()
                .statusCode(200);

        // Before, the merge ran first and the journal refused its record after: the worktree kept a merge nobody
        // recorded. Now the refusal comes first, under the same lock as the record.
        api().post("/api/missions/" + m + "/files/update-agents")
                .then()
                .statusCode(409)
                .body("code", equalTo("MISSION_CLOSED"));
        assertThat(workspaces.head(worktree)).isEqualTo(base);
        assertThat(worktree.resolve("notes.txt")).doesNotExist();
    }
}
