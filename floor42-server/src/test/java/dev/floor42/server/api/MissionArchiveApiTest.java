package dev.floor42.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.Actor;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionArchived;
import dev.floor42.domain.event.MissionEvent.MissionUnarchived;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The archives over HTTP (ADR-0054): a closed mission leaves the list for the archives and comes back; the bulk archive
 * records one event per mission it archives and says, per mission, why it left one out. No session runs: each mission
 * stops before its plan is approved.
 */
@QuarkusTest
class MissionArchiveApiTest extends ApiTestBase {

    @Inject
    Journal journal;

    /** A mission whose plan Floor42 proposed and nobody approved: nothing runs on it. */
    String proposed(Path repo, String title) throws InterruptedException {
        String id = api().body("{\"title\":\"" + title + "\",\"objective\":\"Describe it\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        LiveTestSupport.await(
                "the plan of " + id,
                Duration.ofSeconds(30),
                () -> "AWAITING_APPROVAL"
                        .equals(api().get("/api/missions/" + id)
                                .then()
                                .extract()
                                .path("status")));
        return id;
    }

    void cancel(String id) {
        api().body("{\"reason\":\"not needed\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
    }

    List<String> listed(boolean archived) {
        return api().get("/api/missions?archived=" + archived)
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    List<EventEnvelope> archiveEvents(String id) {
        return journal.readAll().stream()
                .filter(e -> e.mission() != null && e.mission().value().equals(id))
                .filter(e -> e.event() instanceof MissionArchived || e.event() instanceof MissionUnarchived)
                .toList();
    }

    @Test
    void aClosedMissionGoesToTheArchivesAndComesBack() throws Exception {
        String id = proposed(LiveMissionTest.gitRepository(), "Archive journey");
        api().post("/api/missions/" + id + "/archived")
                .then()
                .statusCode(409)
                .body("code", equalTo("MISSION_STILL_OPEN"));
        assertThat(archiveEvents(id)).isEmpty();

        cancel(id);
        api().post("/api/missions/" + id + "/archived")
                .then()
                .statusCode(200)
                .body("archived", equalTo(true))
                .body("status", equalTo("CANCELLED"));
        assertThat(listed(false)).doesNotContain(id);
        assertThat(listed(true)).contains(id);
        assertThat(api().get("/api/missions/archivable").then().extract().<List<String>>path("id"))
                .doesNotContain(id);
        api().get("/api/missions/" + id).then().statusCode(200).body("archived", equalTo(true));

        api().delete("/api/missions/" + id + "/archived")
                .then()
                .statusCode(200)
                .body("archived", equalTo(false))
                .body("outcome", equalTo("not needed"));
        assertThat(listed(false)).contains(id);
        assertThat(listed(true)).doesNotContain(id);

        assertThat(archiveEvents(id)).hasSize(2).allSatisfy(e -> {
            assertThat(e.actor()).isEqualTo(Actor.director());
            assertThat(e.origin()).isEqualTo(Origin.REAL);
        });
        assertThat(archiveEvents(id).getFirst().event()).isInstanceOf(MissionArchived.class);
    }

    @Test
    void archivingEveryClosedMissionRecordsOneEventEachAndSaysWhyItLeftOneOut() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String first = proposed(repo, "Clean the board 1");
        String second = proposed(repo, "Clean the board 2");
        String open = proposed(repo, "Still open");
        cancel(first);
        cancel(second);

        List<String> archivable = api().get("/api/missions/archivable")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        assertThat(archivable).contains(first, second).doesNotContain(open);

        List<Map<String, Object>> outcomes = api().body(Map.of("missions", List.of(first, second, open, "M-999999")))
                .post("/api/missions/archive")
                .then()
                .statusCode(200)
                .extract()
                .path("");
        assertThat(outcomes)
                .extracting(o -> o.get("mission") + " " + o.get("archived") + " " + o.get("code"))
                .containsExactly(
                        first + " true null",
                        second + " true null",
                        open + " false MISSION_STILL_OPEN",
                        "M-999999 false UNKNOWN_MISSION");
        assertThat(listed(false)).doesNotContain(first, second).contains(open);

        List<Map<String, Object>> again = api().body(Map.of("missions", List.of(first)))
                .post("/api/missions/archive")
                .then()
                .statusCode(200)
                .extract()
                .path("");
        assertThat(again).singleElement().satisfies(o -> assertThat(o).containsEntry("code", "MISSION_ARCHIVED"));
        assertThat(archiveEvents(first)).hasSize(1);
        assertThat(archiveEvents(second)).hasSize(1);
        assertThat(archiveEvents(open)).isEmpty();

        api().body("{\"missions\":[]}").post("/api/missions/archive").then().statusCode(400);
        cancel(open);
    }

    @Test
    void aProjectWhoseMissionsAreAllArchivedStaysListedWithoutALastMission() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String older = proposed(repo, "Project kept 1");
        String newer = proposed(repo, "Project kept 2");
        cancel(older);
        cancel(newer);
        String folder = repo.getFileName().toString();

        api().post("/api/missions/" + newer + "/archived").then().statusCode(200);
        Map<String, Object> one = project(folder);
        assertThat(one).containsEntry("lastMission", older).containsEntry("lastTitle", "Project kept 1");
        assertThat(one.get("missions")).isEqualTo(List.of(older));

        api().post("/api/missions/" + older + "/archived").then().statusCode(200);
        Map<String, Object> none = project(folder);
        assertThat(none).containsEntry("lastMission", null).containsEntry("lastTitle", null);
        assertThat(none.get("missions")).isEqualTo(List.of());
        assertThat(none.get("lastActivity")).as("its activity still counts").isNotNull();
    }

    Map<String, Object> project(String folder) {
        List<Map<String, Object>> all =
                api().get("/api/projects").then().statusCode(200).extract().path("");
        return all.stream()
                .filter(p -> String.valueOf(p.get("path")).replace('\\', '/').endsWith("/" + folder))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anArchivedMissionStaysInTheCostsMarkedArchived() throws Exception {
        String id = proposed(LiveMissionTest.gitRepository(), "Costs kept");
        cancel(id);
        api().post("/api/missions/" + id + "/archived").then().statusCode(200);
        List<Map<String, Object>> missions =
                api().get("/api/costs").then().statusCode(200).extract().path("missions");
        assertThat(missions)
                .filteredOn(m -> id.equals(m.get("mission")))
                .singleElement()
                .satisfies(m -> assertThat(m).containsEntry("archived", true));
    }

    @Test
    void aMissionsWriteScriptCannotArchiveNorRestore() throws Exception {
        String id = proposed(LiveMissionTest.gitRepository(), "Scope");
        cancel(id);
        String narrow = api().body("{\"scope\":\"missions:write\",\"days\":1,\"label\":\"script\"}")
                .post("/api/auth/tokens")
                .then()
                .statusCode(201)
                .extract()
                .path("token");
        RequestSpecification script =
                given().header("Authorization", "Bearer " + narrow).contentType("application/json");
        script.post("/api/missions/" + id + "/archived").then().statusCode(403);
        script.body(Map.of("missions", List.of(id)))
                .post("/api/missions/archive")
                .then()
                .statusCode(403);
        api().post("/api/missions/" + id + "/archived").then().statusCode(200);
        given().header("Authorization", "Bearer " + narrow)
                .delete("/api/missions/" + id + "/archived")
                .then()
                .statusCode(403);
        assertThat(archiveEvents(id)).hasSize(1);
    }

    @Test
    void withRetrospectivesOffTheConveningIsAbsentAndSaysWhy() throws Exception {
        String id = proposed(LiveMissionTest.gitRepository(), "No retro here");
        cancel(id);
        api().get("/api/missions/" + id + "/retro/offer")
                .then()
                .statusCode(200)
                .body("available", equalTo(false))
                .body("code", equalTo("RETRO_DISABLED"));
        api().post("/api/missions/" + id + "/retro/convene")
                .then()
                .statusCode(409)
                .body("code", equalTo("RETRO_DISABLED"));
    }
}
