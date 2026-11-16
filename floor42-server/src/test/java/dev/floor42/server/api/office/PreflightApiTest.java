package dev.floor42.server.api.office;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.api.mission.LiveMissionTest;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** GET /api/preflight (ADR-0070): derived from files, git and the journal's state; it records nothing. */
@QuarkusTest
class PreflightApiTest extends ApiTestBase {

    @Inject
    Journal journal;

    private static Path mavenRepo() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        Files.writeString(repo.resolve("pom.xml"), "<project/>");
        Files.writeString(repo.resolve("mvnw"), "#!/bin/sh\n");
        return repo;
    }

    @Test
    void preflightOfAMavenFolderNamesTheToolAndProposesChecks() throws Exception {
        Path repo = mavenRepo(); // pom.xml and mvnw are not committed: a dirty tree
        api().queryParam("project", repo.toString())
                .get("/api/preflight")
                .then()
                .statusCode(200)
                .body("profile.buildTool", equalTo("Maven"))
                .body("profile.files", hasItem("mvnw"))
                .body("profile.branch", equalTo("main"))
                .body("profile.suggestedChecks", equalTo("./mvnw -q verify"))
                .body("profile.lastChecks", nullValue())
                .body("lines.code", hasItem("repo.dirty"))
                .body("verdict", equalTo("WARNINGS"));
    }

    @Test
    void preflightProposesTheChecksOfTheLastMissionOnTheProject() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"First\",\"objective\":\"Do a thing\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        api().body("{\"command\":\"make check\",\"automatic\":false}")
                .put("/api/missions/" + id + "/checks")
                .then()
                .statusCode(200);
        api().queryParam("project", repo.toString())
                .get("/api/preflight")
                .then()
                .statusCode(200)
                .body("profile.lastChecks", equalTo("make check"))
                .body("lines.code", not(hasItem("checks.none")));
    }

    @Test
    void preflightReadsJournalsNothing() throws Exception {
        Path repo = mavenRepo();
        int before = journal.readAll().size();
        api().queryParam("project", repo.toString())
                .get("/api/preflight")
                .then()
                .statusCode(200);
        assertThat(journal.readAll()).hasSize(before);
    }

    @Test
    void preflightOfAMissingFolderIs404() {
        api().queryParam(
                        "project",
                        Path.of(System.getProperty("java.io.tmpdir"), "floor42-no-such-folder")
                                .toString())
                .get("/api/preflight")
                .then()
                .statusCode(404);
    }

    @Test
    void preflightNeedsADirectorSession() throws Exception {
        given().queryParam("project", mavenRepo().toString())
                .get("/api/preflight")
                .then()
                .statusCode(401);
    }
}
