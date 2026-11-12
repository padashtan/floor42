package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.server.app.Workspaces;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The archive wall reads the repository's commits and what each agent's branch holds beyond them, from git. */
@QuarkusTest
class ArchiveApiTest extends ApiTestBase {

    @Inject
    Workspaces workspaces;

    @Test
    void theWallShowsTheTrunkAndEachAgentsCommitsBeyondIt() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Archive\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        Path wt = workspaces.worktree(repo.toString(), new MissionId(id), new AgentId("chris"));
        Files.writeString(wt.resolve("notes.md"), "notes\n");
        LiveMissionTest.run(wt, "git", "add", ".");
        LiveMissionTest.run(
                wt,
                "git",
                "-c",
                "user.name=chris",
                "-c",
                "user.email=chris@localhost",
                "commit",
                "-q",
                "-m",
                "Add notes");

        api().get("/api/missions/" + id + "/archive")
                .then()
                .statusCode(200)
                .body("available", equalTo(true))
                .body("branch", equalTo("main"))
                .body("trunk.subject", contains("init"))
                .body("branches", hasSize(1))
                .body("branches[0].agent", equalTo("chris"))
                .body("branches[0].ahead", equalTo(1))
                .body("branches[0].commits.subject", contains("Add notes"))
                .body("branches[0].commits[0].author", equalTo("chris"));
        api().get("/api/missions/M-999/archive").then().statusCode(404);
        given().get("/api/missions/" + id + "/archive").then().statusCode(401);
    }

    @Test
    void theGraphGivesEveryHeadAndEachCommitsParentsSoMergesCanBeDrawn() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Graph\",\"objective\":\"Write notes\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        Path wt = workspaces.worktree(repo.toString(), new MissionId(id), new AgentId("chris"));
        commit(wt, "notes.md", "Add notes");
        String branch = workspaces.branch(wt);
        // The director merges the agent's branch, then the agent goes on: a merge, and a lane beside the trunk.
        LiveMissionTest.run(
                repo,
                "git",
                "-c",
                "user.name=director",
                "-c",
                "user.email=director@localhost",
                "merge",
                "-q",
                "--no-ff",
                "-m",
                "Merge notes",
                branch);
        commit(wt, "more.md", "More notes");
        String main = workspaces.head(repo);
        String tip = workspaces.head(wt);

        var graph = api().get("/api/missions/" + id + "/archive")
                .then()
                .statusCode(200)
                .body("graph.truncated", equalTo(false))
                .body("graph.heads.agent", contains(null, "chris"))
                .body("graph.heads[0].branch", equalTo("main"))
                .body("graph.heads[0].commit", equalTo(main))
                .body("graph.heads[1].branch", equalTo(branch))
                .body("graph.heads[1].commit", equalTo(tip))
                .body("graph.commits", hasSize(4))
                .extract()
                .jsonPath();
        java.util.List<String> subjects = graph.getList("graph.commits.subject");
        // Children before parents: the merge and the agent's last commit above "Add notes", "init" last.
        org.assertj.core.api.Assertions.assertThat(subjects)
                .containsExactlyInAnyOrder("Merge notes", "More notes", "Add notes", "init");
        org.assertj.core.api.Assertions.assertThat(subjects.getLast()).isEqualTo("init");
        org.assertj.core.api.Assertions.assertThat(subjects.indexOf("Add notes"))
                .isGreaterThan(subjects.indexOf("Merge notes"))
                .isGreaterThan(subjects.indexOf("More notes"));
        java.util.List<String> mergeParents =
                graph.getList("graph.commits.find { it.subject == 'Merge notes' }.parents");
        org.assertj.core.api.Assertions.assertThat(mergeParents).hasSize(2);
    }

    private static void commit(Path wt, String file, String message) throws Exception {
        Files.writeString(wt.resolve(file), message + "\n");
        LiveMissionTest.run(wt, "git", "add", ".");
        LiveMissionTest.run(
                wt, "git", "-c", "user.name=chris", "-c", "user.email=chris@localhost", "commit", "-q", "-m", message);
    }
}
