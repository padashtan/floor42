package dev.floor42.server.iam;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.condition.OS.LINUX;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import dev.floor42.domain.DenialReason;
import dev.floor42.domain.event.ActionEvent.ActionDenied;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

/** The real socket lookup: only the very process Floor42 launched may use the session's token. */
@QuarkusTest
@TestProfile(IamSessionProfile.class)
@EnabledOnOs({WINDOWS, LINUX})
class ProcessBoundHookTest extends IamSessionTestBase {

    private String hookUrl() {
        return "http://127.0.0.1:" + RestAssured.port + "/hooks/pre-tool-use";
    }

    @Test
    void theBoundProcessGetsItsVerdict() throws Exception {
        String token =
                liveSession(realMissionInProgress(), ProcessHandle.current().pid()); // the test JVM plays Claude Code
        given().contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .body(preToolUse("Read", "own-1"))
                .post("/hooks/pre-tool-use")
                .then()
                .statusCode(200);
    }

    @Test
    void childProcessWithTheRightTokenIsWrongProcess() throws Exception {
        Setup s = realMissionInProgress();
        String token = liveSession(s, ProcessHandle.current().pid());
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process child = new ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        ChildHookCall.class.getName(),
                        hookUrl(),
                        token)
                .redirectErrorStream(true)
                .start();
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();

        assertThat(out).contains("401").contains("WRONG_PROCESS");
        assertThat(out).as("the answer never repeats the token").doesNotContain(token);
        assertThat(journal.readAll())
                .anySatisfy(e -> assertThat(e.event())
                        .isInstanceOf(ActionDenied.class)
                        .extracting("reason", "detail")
                        .containsExactly(DenialReason.BLOCKED_UPSTREAM, "identity: WRONG_PROCESS"));
        assertThat(journalText()).doesNotContain("eyJ");
    }
}
