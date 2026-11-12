package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The folder browser lists folder names only, marks git repositories, and reads them with git. Director only. */
@QuarkusTest
class ProjectsApiTest {

    @Inject
    SessionToken token;

    @TempDir
    Path dir;

    io.restassured.specification.RequestSpecification api() {
        return given().header("Authorization", "Bearer " + token.value());
    }

    @Test
    void theBrowserShowsFoldersAndWhichAreRepositories() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("b-project"));
        Process init = new ProcessBuilder("git", "init", "-q", "-b", "main")
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        init.getInputStream().readAllBytes();
        org.assertj.core.api.Assertions.assertThat(init.waitFor()).isZero();
        Files.createDirectory(dir.resolve("a-docs"));
        Files.createDirectory(dir.resolve(".cache"));
        Files.writeString(dir.resolve("secret.txt"), "never listed");

        api().queryParam("path", dir.toString())
                .get("/api/projects/folders")
                .then()
                .statusCode(200)
                .body("folders.name", contains("a-docs", "b-project"))
                .body("folders.repository", contains(false, true))
                .body("git", nullValue())
                .body("truncated", equalTo(false));
        api().queryParam("path", repo.toString())
                .get("/api/projects/folders")
                .then()
                .statusCode(200)
                .body("git.branch", equalTo("main"))
                .body("parent", equalTo(dir.toString()));
        api().get("/api/projects/folders").then().statusCode(200).body("folders[0].path", notNullValue());
        api().queryParam("path", dir.resolve("missing").toString())
                .get("/api/projects/folders")
                .then()
                .statusCode(404);
        api().get("/api/projects").then().statusCode(200);
        given().get("/api/projects/folders").then().statusCode(401);
    }

    @Test
    void recentProjectsArePinnedFirstAndForgottenOnTheDirectorsWord() throws Exception {
        java.util.List<String> repos = new java.util.ArrayList<>();
        for (String title : java.util.List.of("Older", "Newer")) {
            Path repo = LiveMissionTest.gitRepository();
            repos.add(repo.toString());
            String id = api().contentType("application/json")
                    .body("{\"title\":\"" + title + "\",\"objective\":\"Describe it\",\"repository\":\""
                            + repo.toString().replace("\\", "\\\\") + "\"}")
                    .post("/api/missions")
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("id");
            api().contentType("application/json")
                    .body("{\"reason\":\"listing only\"}")
                    .post("/api/missions/" + id + "/cancel")
                    .then()
                    .statusCode(200);
        }
        String older = repos.get(0).replace("\\", "\\\\");
        String newer = repos.get(1);

        io.restassured.path.json.JsonPath list =
                api().get("/api/projects").then().statusCode(200).extract().jsonPath();
        java.util.List<String> paths = list.getList("path");
        assertThatIndex(paths, newer).isLessThan(paths.indexOf(repos.get(0)));
        org.assertj.core.api.Assertions.assertThat(
                        list.getString("find { it.path == '" + newer.replace("\\", "\\\\") + "' }.lastTitle"))
                .isEqualTo("Newer");

        api().contentType("application/json")
                .body("{\"path\":\"" + older + "\",\"pinned\":true}")
                .post("/api/projects/pin")
                .then()
                .statusCode(200)
                .body("[0].path", equalTo(repos.get(0)))
                .body("[0].pinned", equalTo(true))
                .body("[0].lastActivity", notNullValue());
        api().contentType("application/json")
                .body("{\"path\":\"" + older + "\"}")
                .post("/api/projects/forget")
                .then()
                .statusCode(200)
                .body("find { it.path == '" + older + "' }.forgotten", equalTo(true))
                .body("find { it.path == '" + older + "' }.pinned", equalTo(false));
        api().contentType("application/json")
                .body("{\"path\":\"C:\\\\nowhere\\\\at-all\",\"pinned\":true}")
                .post("/api/projects/pin")
                .then()
                .statusCode(404)
                .body("code", equalTo("UNKNOWN_PROJECT"));
    }

    static org.assertj.core.api.AbstractIntegerAssert<?> assertThatIndex(java.util.List<String> list, String item) {
        return org.assertj.core.api.Assertions.assertThat(list.indexOf(item)).isNotNegative();
    }

    @Test
    void aFolderWithoutGitBecomesARepositoryWhenTheDirectorAsks() throws Exception {
        Path empty = Files.createDirectory(dir.resolve("garden-planner"));
        api().queryParam("path", empty.toString())
                .get("/api/projects/init")
                .then()
                .statusCode(200)
                .body("repository", equalTo(false))
                .body("files", equalTo(0))
                .body("refused", nullValue());

        api().contentType("application/json")
                .body("{\"path\":\"" + empty.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/projects/init")
                .then()
                .statusCode(200)
                .body("commit", notNullValue())
                .body("subject", equalTo("Start of the project"))
                .body("uncommitted", equalTo(0));

        // Twice is refused: the repository has its commit now.
        api().contentType("application/json")
                .body("{\"path\":\"" + empty.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/projects/init")
                .then()
                .statusCode(409)
                .body("code", equalTo("INIT_REFUSED"));
    }

    @Test
    void theFilesAlreadyThereGoInTheFirstCommit() throws Exception {
        Path notes = Files.createDirectories(dir.resolve("notes").resolve("drafts"));
        Files.writeString(notes.resolve("idea.md"), "a garden planner");
        Files.writeString(dir.resolve("notes").resolve("todo.txt"), "water");
        Path folder = dir.resolve("notes");

        api().queryParam("path", folder.toString())
                .get("/api/projects/init")
                .then()
                .body("files", equalTo(2));
        api().contentType("application/json")
                .body("{\"path\":\"" + folder.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/projects/init")
                .then()
                .statusCode(200)
                .body("subject", equalTo("Start of the project: the files already there"))
                .body("uncommitted", equalTo(0));
    }

    @Test
    void theHomeFolderAndADriveRootAreRefused() {
        String home = System.getProperty("user.home");
        api().queryParam("path", home)
                .get("/api/projects/init")
                .then()
                .statusCode(200)
                .body("refused", org.hamcrest.Matchers.containsString("home folder"));
        String root = Path.of(home).getRoot().toString();
        api().contentType("application/json")
                .body("{\"path\":\"" + root.replace("\\", "\\\\") + "\"}")
                .post("/api/projects/init")
                .then()
                .statusCode(400)
                .body("code", equalTo("INIT_REFUSED"));
    }
}
