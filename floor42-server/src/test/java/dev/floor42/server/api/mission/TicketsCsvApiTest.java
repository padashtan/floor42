package dev.floor42.server.api.mission;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.session.MissionGovernor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.HookRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Jira export over HTTP: one row per ticket, a file name, a type; a director session only. */
@QuarkusTest
class TicketsCsvApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    HookRegistry hooks;

    @Test
    void aMissionExportsOneRowPerTicket() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body(Map.of("title", "Export", "objective", "Describe it", "repository", repo.toString()))
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        int tickets =
                api().get("/api/missions/" + id).<List<Object>>path("tickets").size();
        Response r = api().get("/api/missions/" + id + "/tickets.csv");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.contentType()).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(r.header("Content-Disposition")).contains("attachment", "floor42-" + id + "-tickets.csv");
        List<List<String>> rows = TicketsCsvTest.parse(r.asString());
        assertThat(rows.get(0)).startsWith("Issue ID", "Summary");
        assertThat(rows).hasSize(tickets + 1);
    }

    @Test
    void anUnknownMissionIs404() {
        api().get("/api/missions/M-999/tickets.csv").then().statusCode(404);
    }

    @Test
    void aDirectorSessionIsRequired() {
        given().get("/api/missions/M-1/tickets.csv").then().statusCode(401);
    }

    /** The export only reads: a read script token gets the file. */
    @Test
    void aReadTokenCanExport() throws Exception {
        String id = mission();
        String token = api().body("{\"scope\":\"read\",\"days\":1,\"label\":\"csv test\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        given().header("Authorization", "Bearer " + token)
                .get("/api/missions/" + id + "/tickets.csv")
                .then()
                .statusCode(200);
    }

    /** An agent session's credential never reaches the export: the guard answers 403. */
    @Test
    void anAgentCredentialIsRefused() throws Exception {
        String id = mission();
        MissionId m = new MissionId(id);
        String repo = LiveMissionTest.gitRepository().toString();
        var spec = new SessionSpec(
                m,
                office.query(c -> c.tickets().of(m).getFirst().id()),
                new AgentId("chris"),
                Origin.REAL,
                repo,
                "",
                "claude-sonnet-5-5",
                "medium",
                List.of("Read"));
        String credential = hooks.register(new MissionGovernor(office, spec));
        try {
            given().header("Authorization", "Bearer " + credential)
                    .get("/api/missions/" + id + "/tickets.csv")
                    .then()
                    .statusCode(403);
        } finally {
            hooks.unregister(credential);
        }
    }

    private String mission() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        return api().body(Map.of("title", "Export", "objective", "Describe it", "repository", repo.toString()))
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
