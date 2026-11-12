package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.TicketStatus;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The plan's usage limit, end to end with the fake Claude Code: the producer's session reports its 5-hour window at 97%,
 * so the review session is held and the director asked; waiting for the reset resumes it once the window starts again.
 */
@QuarkusTest
class PlanLimitApiTest extends QuestionApiTestBase {

    @Test
    void aSessionTheLimitWouldCutWaitsForTheDirector() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Near the limit\",\"objective\":\"Write notes [fake:usage=0.97]\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
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
        answer(m, "ALLOW"); // git status
        answer(m, "ALLOW"); // the write

        await("the plan-limit question", Duration.ofSeconds(20), () -> {
            Question q = open(m);
            return q != null && q.kind() == QuestionKind.PLAN_LIMIT;
        });
        Question q = open(m);
        assertThat(q.context()).contains("5-hour window at 97% (measured by Claude Code)");
        int before = office.query(c -> c.sessions().of(m).size());
        assertThat(before).as("the review has not started").isEqualTo(1);
        api().get("/api/usage")
                .then()
                .statusCode(200)
                .body("windows[0].window", org.hamcrest.Matchers.equalTo("five_hour"))
                .body("windows[0].utilization", org.hamcrest.Matchers.equalTo(0.97f));

        answer(m, "Wait for the reset");

        await(
                "the review, after the reset",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).getFirst().status() == TicketStatus.DONE));
        int after = office.query(c -> c.sessions().of(m).size());
        assertThat(after).isEqualTo(2);
        // The journal is shared by every test: leave no window at risk behind.
        await(
                "the window to reset",
                Duration.ofSeconds(20),
                () -> office.query(c -> c.verification().planUsage().latest().values().stream()
                        .noneMatch(r -> r.current(java.time.Instant.now()))));
        api().body("{\"reason\":\"test done\"}")
                .post("/api/missions/" + id + "/cancel")
                .then()
                .statusCode(200);
    }
}
