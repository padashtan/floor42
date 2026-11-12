package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A ticket the director drops while an agent works on it: the session is stopped at once, with the reason. */
@QuarkusTest
class CancelledTicketApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Test
    void droppingATicketStopsTheSessionWorkingOnIt() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = new MissionId(api().body("{\"title\":\"Dropped\",\"objective\":\"Describe this repository"
                        + " [fake:hang]\",\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await(
                "the producer at work",
                Duration.ofSeconds(60),
                () -> office.query(c -> c.sessions().of(m).stream().anyMatch(AgentSession::isOpen)));
        AgentSession working = office.query(c -> c.sessions().of(m).stream()
                .filter(AgentSession::isOpen)
                .findFirst()
                .orElseThrow());
        TicketId ticket = working.ticket();

        api().body("{\"reason\":\"not needed\"}")
                .post("/api/tickets/" + ticket.value() + "/drop")
                .then()
                .statusCode(200);

        await(
                "the session stopped",
                Duration.ofSeconds(20),
                () -> office.query(
                        c -> c.sessions().find(working.id()).orElseThrow().endedAt() != null));
        AgentSession ended = office.query(c -> c.sessions().find(working.id()).orElseThrow());
        // Journaled as a failed session right after the ticket's cancellation, whose reason precedes it.
        assertThat(ended.failed()).isTrue();
    }
}
