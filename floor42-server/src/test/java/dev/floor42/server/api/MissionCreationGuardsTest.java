package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** A mission is created once per Idempotency-Key, and its title and objective have a length the board can hold. */
@QuarkusTest
class MissionCreationGuardsTest extends ApiTestBase {

    @Inject
    OfficeService office;

    static String body(String title, String objective, Path repo) {
        return "{\"title\":\"" + title + "\",\"objective\":\"" + objective + "\",\"repository\":\""
                + repo.toString().replace("\\", "\\\\") + "\"}";
    }

    int missions() {
        return office.query(c -> c.missions().all().size());
    }

    @Test
    void twoIdenticalRequestsWithOneKeyCreateOneMission() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String key = UUID.randomUUID().toString();
        int before = office.query(c -> c.missions().all().size());
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Response>> sent = List.of(1, 2).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        // Handled: the test thread is ending; the request is sent anyway
                        Thread.currentThread().interrupt();
                    }
                    return api().header("Idempotency-Key", key)
                            .body(body("Twice sent", "Describe this repository", repo))
                            .post("/api/missions");
                }))
                .toList();
        go.countDown();
        List<Response> answers = sent.stream().map(CompletableFuture::join).toList();

        assertThat(answers).extracting(Response::statusCode).containsExactlyInAnyOrder(201, 200);
        assertThat(answers.get(0).<String>path("id")).isEqualTo(answers.get(1).path("id"));
        assertThat(missions()).isEqualTo(before + 1);

        // The same key for another mission is a client's mistake, said as such.
        api().header("Idempotency-Key", key)
                .body(body("Another one", "Describe this repository", repo))
                .post("/api/missions")
                .then()
                .statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void aTitleOrAnObjectiveTooLongIsRefused() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        int before = office.query(c -> c.missions().all().size());
        api().body(body("x".repeat(MissionsResource.TITLE_MAX + 1), "Describe this repository", repo))
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("TITLE_TOO_LONG"));
        api().body(body("Long brief", "x".repeat(MissionsResource.OBJECTIVE_MAX + 1), repo))
                .post("/api/missions")
                .then()
                .statusCode(400)
                .body("code", equalTo("OBJECTIVE_TOO_LONG"));
        assertThat(missions()).isEqualTo(before);
    }
}
