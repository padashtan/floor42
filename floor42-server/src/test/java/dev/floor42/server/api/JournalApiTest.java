package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import dev.floor42.server.runtime.AgentRuntime;
import dev.floor42.server.runtime.HookRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** The journal's health and its copies: the director's only (ADR-0042). */
@QuarkusTest
class JournalApiTest extends ApiTestBase {

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @Inject
    HookRegistry hooks;

    @Test
    void theDirectorListsBackupsTakesOneAndPreparesARestore() {
        api().get("/api/journal/unreadable").then().statusCode(200).body("size()", equalTo(0));
        // Tests copy to the temporary folder: never ~/.floor42, never inside this repository.
        api().get("/api/backups")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true))
                .body("disabledReason", nullValue())
                .body("folder", startsWith(System.getProperty("java.io.tmpdir").replaceAll("[\\\\/]+$", "")))
                .body("startupKeep", equalTo(5))
                .body("dailyKeep", equalTo(7));

        String name = api().post("/api/backups")
                .then()
                .statusCode(200)
                .body("backups.kind", hasItem("MANUAL"))
                .body("backups[0].events", notNullValue())
                .extract()
                .path("backups.find { it.kind == 'MANUAL' }.name");

        api().body("{\"name\":\"../floor42.db\"}")
                .put("/api/backups/restore")
                .then()
                .statusCode(404);
        api().body("{}").put("/api/backups/restore").then().statusCode(400);
        try {
            api().body("{\"name\":\"" + name + "\"}")
                    .put("/api/backups/restore")
                    .then()
                    .statusCode(200)
                    .body("pendingRestore", equalTo(name));
            // Kept in the daemon's memory: no file a start would read, so nothing an agent could have written either.
            assertThat(Files.exists(dataDir.resolve("restore-pending.properties")))
                    .isFalse();
        } finally {
            api().delete("/api/backups/restore").then().statusCode(200).body("pendingRestore", nullValue());
        }
        api().get("/api/journal/alerts")
                .then()
                .statusCode(200)
                .body("refusedRestore", nullValue())
                .body("locked", nullValue())
                .body("continuity", nullValue())
                .body("configFiles.size()", equalTo(0));
        api().post("/api/journal/alerts/continuity/acknowledge")
                .then()
                .statusCode(200)
                .body("continuity", nullValue());
    }

    @Test
    void anAnchorFailedBreakShowsNoCountsAndNothingRecorded() {
        var view = JournalResource.view(new dev.floor42.server.journal.JournalContinuity.Break(
                dev.floor42.server.journal.JournalContinuity.ANCHOR_FAILED, null, -1, -1));

        assertThat(view.reason()).isEqualTo("ANCHOR_FAILED");
        assertThat(view.foundEvents()).isNull();
        assertThat(view.foundLastSequence()).isNull();
        assertThat(view.recordedEvents()).isNull();
        assertThat(view.recordedLastSequence()).isNull();
        assertThat(view.displaced()).isNull();
    }

    @Test
    void anAgentSessionsCredentialIsRefused() {
        String credential = hooks.register(new AgentRuntime.Governor() {
            @Override
            public AgentRuntime.Verdict decide(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
                return null;
            }

            @Override
            public void completed(dev.floor42.server.runtime.RuntimeEvent.ToolCompleted result) {}
        });
        try {
            for (String path : List.of("/api/journal/unreadable", "/api/journal/alerts", "/api/backups")) {
                given().header("Authorization", "Bearer " + credential)
                        .get(path)
                        .then()
                        .statusCode(403);
            }
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .post("/api/backups")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .post("/api/journal/alerts/continuity/acknowledge")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .contentType("application/json")
                    .body("{\"name\":\"x.db\"}")
                    .put("/api/backups/restore")
                    .then()
                    .statusCode(403);
            given().header("Authorization", "Bearer " + credential)
                    .delete("/api/backups/restore")
                    .then()
                    .statusCode(403);
        } finally {
            hooks.unregister(credential);
        }
    }
}
