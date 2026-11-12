package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StatusResourceTest {

    @Test
    void reportsRealModeWhenLiveAgentsAreAvailable() {
        given().when()
                .get("/api/status")
                .then()
                .statusCode(200)
                .body("mode", equalTo("REAL"))
                .body("runtime.version", equalTo("2.1.304"));
    }

    /** Measured from the kernel at each request: non-dumpable on Linux, unknown (null) where it cannot be asked. */
    @Test
    void reportsWhetherTheDaemonIsNonDumpableAsTheKernelSaysIt() {
        boolean linux = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("linux");
        given().when()
                .get("/api/status")
                .then()
                .statusCode(200)
                .body("process.nonDumpableRequested", equalTo(true))
                // The tests run as the daemon does: -XX:+DisableAttachMechanism (surefire argLine), read from the JVM.
                .body("process.attachDisabled", equalTo(true))
                .body("process.nonDumpable", linux ? equalTo(true) : org.hamcrest.Matchers.nullValue());
    }

    @Test
    void reportsTheCommitTheDaemonWasBuiltFrom() throws Exception {
        // The tests run from a git checkout, so the build wrote the commit; a Docker build leaves the fields null.
        given().when()
                .get("/api/status")
                .then()
                .statusCode(200)
                .body("build.commit", matchesPattern("[0-9a-f]{7,}"))
                .body("build.commitFull", equalTo(head()));
    }

    /** The commit checked out where the build ran, a linked worktree included (where JGit read the wrong HEAD). */
    static String head() throws Exception {
        Process git = new ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        String out = new String(git.getInputStream().readAllBytes()).strip();
        git.waitFor();
        return out;
    }

    @Test
    void thePageIsNeverCachedSoARedeployShowsTheNewScreen() {
        given().when().get("/").then().header("Cache-Control", equalTo("no-cache"));
    }
}
