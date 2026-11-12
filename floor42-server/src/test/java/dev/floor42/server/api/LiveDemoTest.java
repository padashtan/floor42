package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.MissionScheduler;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SchedulerQuiet;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A blocking question suspends the work that depends on it, and only that work, until the director answers. */
@QuarkusTest
class LiveDemoTest {

    @Inject
    SessionToken token;

    @Inject
    OfficeService office;

    @Inject
    MissionScheduler scheduler;

    String startLive() {
        return given().header("Authorization", "Bearer " + token.value())
                .post("/api/demo/live?paceMs=0")
                .then()
                .statusCode(202)
                .extract()
                .path("mission");
    }

    String openApproval(MissionId m) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.ACTION_APPROVAL && q.status() == QuestionStatus.OPEN)
                .map(q -> q.id().value())
                .findFirst()
                .orElse(null));
    }

    java.util.List<String> tools(MissionId m) {
        return office.query(c -> c.actions().of(m).stream().map(a -> a.tool()).toList());
    }

    TicketStatus inspectTicket(MissionId m) {
        return office.query(c -> c.tickets().of(m).getFirst().status());
    }

    void answer(String question, String answer) {
        given().header("Authorization", "Bearer " + token.value())
                .contentType("application/json")
                .body("{\"answer\":\"" + answer + "\"}")
                .post("/api/questions/" + question + "/answer")
                .then()
                .statusCode(200);
    }

    @Test
    void theDemoWaitsForTheDirectorThenGoesOn() throws Exception {
        MissionId m = new MissionId(startLive());
        await("the approval request", Duration.ofSeconds(5), () -> openApproval(m) != null);
        String question = openApproval(m);

        // The demo plays as a task of the MissionScheduler: blocked on the director, it never ends; at pace 0 a demo
        // not blocked would have played to its end long before 300 ms.
        assertThat(SchedulerQuiet.busyAfter(scheduler, Duration.ofMillis(300), "demo-" + m.value()))
                .as("the demo still waits")
                .containsExactly("demo-" + m.value());
        assertThat(inspectTicket(m))
                .as("blocked while the director has not answered")
                .isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(tools(m))
                .as("like a live session blocked on its hook, nothing runs after the pending call")
                .containsExactly("Bash", "Write");

        answer(question, "DENY");

        await("the independent review", Duration.ofSeconds(5), () -> inspectTicket(m) == TicketStatus.DONE);
        assertThat(tools(m)).containsExactly("Bash", "Write", "Write", "Read");
        long approvals = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.kind() == QuestionKind.ACTION_APPROVAL)
                .count());
        assertThat(approvals).as("one question for both writes").isEqualTo(1L);
        String secondWrite = office.query(c -> c.actions().of(m).stream()
                .filter(a -> a.tool().equals("Write"))
                .skip(1)
                .findFirst()
                .orElseThrow()
                .decisionBasis());
        assertThat(secondWrite).as("decided by the remembered answer").contains("director already denied");
    }

    @Test
    void allowingTheWritesLetsTheRecordedResultsApply() throws Exception {
        MissionId m = new MissionId(startLive());
        await("the approval request", Duration.ofSeconds(5), () -> openApproval(m) != null);

        answer(openApproval(m), "ALLOW");

        await("the ticket to finish", Duration.ofSeconds(5), () -> inspectTicket(m) == TicketStatus.DONE);
        List<String> writes = office.query(c -> c.actions().of(m).stream()
                .filter(a -> a.tool().equals("Write"))
                .map(a -> a.stage() + " " + a.result())
                .toList());
        // The recording really created both files: once allowed, the recorded results are faithful.
        assertThat(writes).hasSize(2).allMatch(w -> w.startsWith("EXECUTED File created successfully"));
    }
}
