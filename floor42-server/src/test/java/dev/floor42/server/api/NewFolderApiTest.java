package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.server.security.SessionRegistry;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The folder browser's new folder: created and listed for the director's full scope only, refusals by their code. */
@QuarkusTest
class NewFolderApiTest {

    @Inject
    SessionToken token;

    @Inject
    SessionRegistry sessions;

    @TempDir
    Path dir;

    String body(Path parent, String name) {
        return "{\"parent\":\"" + parent.toString().replace("\\", "\\\\") + "\",\"name\":\"" + name + "\"}";
    }

    @Test
    void theDirectorCreatesAFolderAndTheBrowserListsIt() throws Exception {
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body(body(dir, "garden"))
                .post("/api/projects/folders")
                .then()
                .statusCode(200)
                .body("path", equalTo(dir.toRealPath().resolve("garden").toString()))
                .body("parent", equalTo(dir.toRealPath().toString()));
        assertThat(Files.isDirectory(dir.resolve("garden"))).isTrue();

        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body(body(dir, "CON"))
                .post("/api/projects/folders")
                .then()
                .statusCode(400)
                .body("code", equalTo("NEW_FOLDER_NAME_INVALID"));
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body(body(dir, "garden"))
                .post("/api/projects/folders")
                .then()
                .statusCode(409)
                .body("code", equalTo("NEW_FOLDER_EXISTS"));
    }

    @Test
    void aNarrowerScopeOrNoCredentialCreatesNothing() {
        String missions = sessions.issueScript(SessionRegistry.Scope.MISSIONS_WRITE, 1, "script", "test")
                .secret();
        given().header("Authorization", "Bearer " + missions)
                .contentType("application/json")
                .body(body(dir, "scripted"))
                .post("/api/projects/folders")
                .then()
                .statusCode(403);
        given().contentType("application/json")
                .body(body(dir, "anonymous"))
                .post("/api/projects/folders")
                .then()
                .statusCode(401);

        assertThat(Files.exists(dir.resolve("scripted"))).isFalse();
        assertThat(Files.exists(dir.resolve("anonymous"))).isFalse();
    }
}
