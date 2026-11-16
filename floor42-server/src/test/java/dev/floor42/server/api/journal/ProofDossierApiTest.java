package dev.floor42.server.api.journal;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.api.ApiTestBase;
import dev.floor42.server.api.mission.LiveMissionTest;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * A mission played by the fake Claude Code whose objective holds a secret and local paths, read as a proof dossier: the
 * body holds none of them, nor the session token, and nothing in the request turns the masking off.
 */
@QuarkusTest
class ProofDossierApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    private String leakyMission() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String objective = "Describe " + repo + " [fake:leak] with key sk-ant-api03-abcdefghijklmnop";
        String id = api().body(Map.of("title", "Leaky", "objective", objective, "repository", repo.toString()))
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
        await(
                "the reviewed delivery",
                Duration.ofSeconds(40),
                () -> office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE);
        return id;
    }

    @Test
    void theDossierHoldsNoSecretNoLocalPathAndNoToken() throws Exception {
        String id = leakyMission();
        Response r = api().get("/api/missions/" + id + "/proof-dossier");
        assertThat(r.statusCode()).isEqualTo(200);
        String body = r.asString();
        List<String> forbidden = new ArrayList<>(List.of(
                token.value(),
                "sk-ant-api03-abcdefghijklmnop",
                "ghp_abcdefghijklmnopqrstuvwxyz0123",
                "abcdefghijklmnop.qrstuvwxyz",
                "hunter2hunter2",
                "C:\\Users\\director",
                "/home/director",
                System.getProperty("user.home"),
                System.getProperty("user.home").replace('\\', '/'),
                System.getProperty("user.home").replace("\\", "\\\\"),
                dataDir.toAbsolutePath().normalize().toString()));
        for (String f : forbidden)
            assertThat(body).as("the dossier must not hold %s", f).doesNotContain(f);
        assertThat(body).contains("[masked]", "\"statement\":", "\"origin\":\"REAL\"");
        List<String> kinds = r.path("masked.kind");
        assertThat(kinds).contains("SECRET");
    }

    @Test
    void nothingTurnsMaskingOff() throws Exception {
        String id = leakyMission();
        String plain = api().get("/api/missions/" + id + "/proof-dossier").asString();
        for (String q : List.of("?masked=false", "?raw=true", "?unmask=1")) {
            String other =
                    api().get("/api/missions/" + id + "/proof-dossier" + q).asString();
            assertThat(readTimesRemoved(other)).isEqualTo(readTimesRemoved(plain));
        }
    }

    /** Both are the moment of a read, so two reads differ by them and by nothing else. */
    private static String readTimesRemoved(String dossier) {
        return dossier.replaceAll("\"(generatedAt|gitReadAt)\":\"[^\"]*\",?", "");
    }

    @Test
    void theDossierFailsClosedWhenTheTokenWouldLeave() {
        assertThatThrownBy(() -> ProofDossierResource.failClosed("{\"x\":\"" + token.value() + "\"}", token.value()))
                .isInstanceOfSatisfying(WebApplicationException.class, e -> {
                    assertThat(e.getResponse().getStatus()).isEqualTo(500);
                    assertThat(e.getResponse().hasEntity()).isFalse();
                });
    }

    @Test
    void theDossierNeedsADirectorSession() {
        given().get("/api/missions/M-1/proof-dossier").then().statusCode(401);
    }

    @Test
    void readingTheDossierJournalsNothingAndDownloadSetsTheNameAndNoStore() throws Exception {
        String id = leakyMission();
        int before = journal.readAll().size();
        Response r = api().get("/api/missions/" + id + "/proof-dossier?download=true");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.header("Content-Disposition")).contains("attachment", "floor42-dossier-" + id + ".json");
        assertThat(r.header("Cache-Control")).contains("no-store");
        assertThat(journal.readAll()).hasSize(before);
    }

    @Test
    void anUnknownMissionHasNoDossier() {
        api().get("/api/missions/M-999/proof-dossier").then().statusCode(404);
    }
}
