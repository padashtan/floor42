package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.TicketEvent.TicketAssigned;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.app.LiveMissions;
import dev.floor42.server.app.MissionScheduler;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SchedulerQuiet;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ticket-by-ticket validation end to end (ADR-0017): the planner proposes, the director edits, approves a selection and
 * drops a ticket; approved tickets run one at a time, the others wait.
 */
@QuarkusTest
class PlanningApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Inject
    MissionScheduler scheduler;

    @Inject
    LiveMissions live;

    Ticket ticket(String id) {
        return office.query(c -> c.tickets().find(new TicketId(id)).orElseThrow());
    }

    List<String> sessions(MissionId m) {
        return office.query(
                c -> c.sessions().of(m).stream().map(s -> s.agent().value()).toList());
    }

    /** Waits for the condition, allowing every permission the producer asks for on the way. */
    void awaitAllowing(MissionId m, String what, java.util.function.BooleanSupplier done) throws Exception {
        await(what, Duration.ofSeconds(40), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return done.getAsBoolean();
        });
    }

    /** A real run gave every planned ticket to the first developer; a developer hired next stayed idle. */
    @Test
    void independentPlannedTicketsGoToDifferentDevelopers() throws Exception {
        api().body("{\"name\":\"Ines\",\"workRole\":\"DEVELOPER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200);
        List<List<dev.floor42.domain.event.EventEnvelope>> batches = new java.util.concurrent.CopyOnWriteArrayList<>();
        AutoCloseable watch = office.subscribe(batches::add);
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = new MissionId(api().body("{\"title\":\"Two things\",\"objective\":\"Describe this repository"
                        + " [fake:independent-plan]\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\")
                        + "\",\"plan\":true}")
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
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).size() == 3));

        // The spread to the second developer is the last thing the plan does.
        await(
                "the spread",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).subList(1, 3).stream()
                                .map(t -> t.assignee().value())
                                .distinct()
                                .count())
                        == 2);
        watch.close();
        int plan = -1;
        for (int i = 0; i < batches.size(); i++) {
            // The last one: the mission's own creation made the planning ticket before.
            if (batches.get(i).stream().anyMatch(e -> e.event() instanceof TicketCreated)) plan = i;
        }
        assertThat(plan).as("the batch of the plan").isNotNegative();
        assertThat(batches.subList(plan + 1, batches.size()))
                .as("the tickets of a plan are never left assigned by a command of their own")
                .noneSatisfy(batch -> assertThat(batch)
                        .isNotEmpty()
                        .allSatisfy(e -> assertThat(e.event()).isInstanceOf(TicketAssigned.class)));
        List<Ticket> proposed = office.query(c -> c.tickets().of(m).subList(1, 3));
        assertThat(proposed)
                .extracting(Ticket::dependsOn)
                .allSatisfy(d -> assertThat(d).isEmpty());
        assertThat(proposed)
                .extracting(t -> t.assignee().value())
                .as("two independent tickets, two developers")
                .doesNotHaveDuplicates();
        String brief = office.query(c -> dev.floor42.server.app.Briefs.planner(
                c,
                c.missions().find(m).orElseThrow(),
                c.tickets().of(m).getFirst(),
                c.staff().find(new dev.floor42.domain.AgentId("galen")).orElseThrow()));
        assertThat(brief).contains("Ines (developer)");
        // Nothing proposed runs: she leaves without work, and the other tests find the first staff as before.
        String ines = office.query(c -> c.staff().all().stream()
                .filter(a -> a.displayName().equals("Ines") && !a.dismissed())
                .findFirst()
                .orElseThrow()
                .id()
                .value());
        api().body("{\"reason\":\"test\"}")
                .post("/api/agents/" + ines + "/dismiss")
                .then()
                .statusCode(200);
    }

    @Test
    void thePlannerProposesAndTheDirectorValidatesTicketByTicket() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Inventory\",\"objective\":\"Describe this repository\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\",\"plan\":true}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .body("tickets[0].title", equalTo("Plan: Inventory"))
                .body("tickets[0].assignee", equalTo("galen"))
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        String planning = office.query(c -> c.tickets().of(m).getFirst().id().value());

        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        await(
                "the plan",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.tickets().of(m).size() == 4));

        List<Ticket> proposed = office.query(c -> c.tickets().of(m).subList(1, 4));
        assertThat(proposed).extracting(Ticket::status).containsOnly(TicketStatus.PROPOSED);
        assertThat(proposed)
                .extracting(Ticket::title)
                .containsExactly("Describe the project", "List the risks", "Rewrite everything");
        assertThat(proposed.get(0).acceptanceCriteria()).containsExactly("README read", "summary written");
        assertThat(proposed.get(1).dependsOn()).containsExactly(proposed.get(0).id());
        assertThat(proposed.get(2).dependsOn())
                .as("unknown numbers ignored")
                .containsExactly(proposed.get(1).id());
        assertThat(ticket(planning).status()).isEqualTo(TicketStatus.DONE);
        assertThat(sessions(m)).containsExactly("galen");
        // Asked to look again, then left until it has nothing left to start, the dispatcher still runs nothing
        // proposed. The control is below: the first ticket runs as soon as the director approves it.
        live.nudge(m);
        SchedulerQuiet.await(scheduler);
        assertThat(sessions(m)).as("nothing proposed runs").containsExactly("galen");

        String first = proposed.get(0).id().value();
        String second = proposed.get(1).id().value();
        String third = proposed.get(2).id().value();
        api().body("{\"title\":\"Describe the project briefly\",\"description\":\"One page\","
                        + "\"acceptanceCriteria\":[\"README read\"]}")
                .put("/api/tickets/" + first)
                .then()
                .statusCode(200);
        api().body("{\"reason\":\"not now\"}")
                .post("/api/tickets/" + third + "/drop")
                .then()
                .statusCode(200);
        api().body("{\"tickets\":[\"" + first + "\"]}")
                .post("/api/missions/" + id + "/tickets/approve")
                .then()
                .statusCode(200);

        awaitAllowing(m, "the first approved ticket", () -> ticket(first).status() == TicketStatus.DONE);
        assertThat(ticket(first).title()).isEqualTo("Describe the project briefly");
        assertThat(ticket(second).status()).as("not approved: waits").isEqualTo(TicketStatus.PROPOSED);
        assertThat(ticket(third).status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(sessions(m)).containsExactly("galen", "chris", "darius");
        api().body("{\"tickets\":[\"" + first + "\"]}")
                .post("/api/missions/" + id + "/tickets/approve")
                .then()
                .statusCode(409)
                .body("code", equalTo("NOT_PROPOSED"));

        api().body("{\"tickets\":[\"" + second + "\"]}")
                .post("/api/missions/" + id + "/tickets/approve")
                .then()
                .statusCode(200);
        awaitAllowing(m, "the second ticket", () -> ticket(second).status() == TicketStatus.DONE);
        assertThat(sessions(m)).containsExactly("galen", "chris", "darius", "chris", "darius");

        api().body("{\"reason\":\"all done\"}")
                .post("/api/missions/" + id + "/complete")
                .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
    }
}
