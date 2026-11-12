package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Priority;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.TicketEvent.TicketCreated;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hiring for a role and working in parallel (ADR-0019): with a second developer, two independent tickets run at the
 * same time, each in its developer's own worktree, and each is reviewed by someone who did not produce it.
 */
@QuarkusTest
class StaffApiTest extends ApiTestBase {

    @Inject
    OfficeService office;

    @Test
    void hiringAndDismissingFollowTheRules() {
        api().body("{\"name\":\"Pat\",\"workRole\":\"PLANNER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(400);
        api().body("{\"name\":\"Pat\",\"workRole\":\"JUGGLER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(400);
        RestAssured.given()
                .contentType("application/json")
                .body("{\"name\":\"Pat\",\"workRole\":\"REVIEWER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(401);

        String id = api().body("{\"name\":\"Pat\",\"workRole\":\"REVIEWER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .body("workRole", equalTo("REVIEWER"))
                .body("role", equalTo("Reviewer"))
                .extract()
                .path("id");
        api().get("/api/agents").then().body("id", hasItem(id));
        api().get("/api/agents/" + id + "/profile")
                .then()
                .body("duties[0]", org.hamcrest.Matchers.startsWith("Reviews"));

        api().body("{\"reason\":\"not needed\"}")
                .post("/api/agents/" + id + "/dismiss")
                .then()
                .statusCode(200);
        api().get("/api/agents").then().body("id", not(hasItem(id)));
        api().get("/api/agents/" + id + "/profile").then().statusCode(200);
    }

    @Test
    void aNameAlreadyAtWorkIsRefusedWhateverItsCase() {
        String first = api().body("{\"name\":\"Margaux\",\"workRole\":\"DEVELOPER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        for (String twin : List.of("Margaux", "margaux", " MARGAUX ", "Mar\u200Bgaux", "\uFF2D\uFF41rgaux")) {
            api().body("{\"name\":\"" + twin + "\",\"workRole\":\"REVIEWER\",\"model\":\"claude-haiku-5-5\"}")
                    .post("/api/agents")
                    .then()
                    .statusCode(409)
                    .body("code", equalTo("NAME_TAKEN"));
        }
        // Once she left, the name is free again; the identifier takes a number, since the journal keeps hers.
        api().body("{\"reason\":\"test\"}")
                .post("/api/agents/" + first + "/dismiss")
                .then()
                .statusCode(200);
        api().body("{\"name\":\"Margaux\",\"workRole\":\"DEVELOPER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .body("name", equalTo("Margaux"))
                .body("id", not(equalTo(first)));
    }

    @Test
    void aModelOutsideTheCatalogueIsRefused() {
        api().body("{\"name\":\"Gaspard\",\"workRole\":\"DEVELOPER\",\"model\":\"gpt-4\"}")
                .post("/api/agents")
                .then()
                .statusCode(400)
                .body("code", equalTo("UNKNOWN_MODEL"));
        api().get("/api/agents").then().body("name", not(hasItem("Gaspard")));
        String id = api().body("{\"name\":\"Gaspard\",\"workRole\":\"DEVELOPER\",\"model\":\"claude-opus-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        api().body("{\"model\":\"gpt-4\",\"effort\":\"low\"}")
                .put("/api/agents/" + id)
                .then()
                .statusCode(400)
                .body("code", equalTo("UNKNOWN_MODEL"));
        api().body("{\"model\":\"claude-opus-5-5\",\"effort\":\"low\"}")
                .put("/api/agents/" + id)
                .then()
                .statusCode(200);
    }

    @Test
    void theFirstStaffAndCommitteeKeepTheirNames() {
        // A newcomer whose name reads like the id of one of Floor42's own agents gets another id: Floor42 never
        // mistakes them for each other.
        api().body("{\"name\":\"Noe!\",\"workRole\":\"DEVELOPER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .body("id", not(equalTo("noe")));
        api().body("{\"name\":\"Chris!\",\"workRole\":\"REVIEWER\",\"model\":\"claude-haiku-5-5\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .body("id", not(equalTo("chris")));
        // Galen plans missions and sits on committees: Floor42 cannot work without him.
        api().body("{\"reason\":\"test\"}")
                .post("/api/agents/galen/dismiss")
                .then()
                .statusCode(409)
                .body("code", equalTo("AGENT_NEEDED"));
    }

    @Test
    void twoDevelopersWorkTwoIndependentTicketsAtOnce() throws Exception {
        // Chris is hired on first need by whichever test came first in this daemon, and another test of this class may
        // have let him go: not a given when this runs first. Any developer at work takes the second ticket.
        AgentId secondDev = office.query(c -> c.staff()
                .find(new AgentId("chris"))
                .filter(a -> !a.dismissed())
                .map(a -> a.id())
                .orElse(null));
        if (secondDev == null) {
            secondDev = new AgentId(
                    api().body("{\"name\":\"Chris\",\"workRole\":\"DEVELOPER\"," + "\"model\":\"claude-sonnet-5-5\"}")
                            .post("/api/agents")
                            .then()
                            .statusCode(200)
                            .extract()
                            .path("id"));
        }
        Path repo = LiveMissionTest.gitRepository();
        String id = api().body("{\"title\":\"Two things\",\"objective\":\"Describe this repository\",\"repository\":\""
                        + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
        MissionId m = new MissionId(id);
        TicketId first = office.query(c -> c.tickets().of(m).getFirst().id());
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + id + "/approve")
                .then()
                .statusCode(200);
        TicketId second = ((TicketCreated) office.execute(c -> c.tickets()
                                .create(
                                        office.context(Actor.system(), Origin.REAL),
                                        m,
                                        "Deliver: a second, independent report",
                                        "Describe this repository",
                                        Priority.NORMAL,
                                        List.of(),
                                        List.of("The report answers the objective")))
                        .getFirst()
                        .event())
                .ticket();
        AgentId takes = secondDev;
        office.execute(c -> c.tickets().assign(office.context(Actor.system(), Origin.REAL), second, takes));
        String ken = api().body("{\"name\":\"Ken\",\"role\":\"Java developer\",\"workRole\":\"DEVELOPER\","
                        + "\"model\":\"claude-sonnet-5-5\",\"effort\":\"medium\"}")
                .post("/api/agents")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        await("both tickets done", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return office.query(c -> c.tickets().find(first).orElseThrow().status() == TicketStatus.DONE
                    && c.tickets().find(second).orElseThrow().status() == TicketStatus.DONE);
        });

        List<AgentSession> sessions = office.query(c -> c.sessions().of(m));
        // The journal is shared by every test: whoever is on staff, the two tickets go to two developers.
        List<AgentId> assignees = office.query(c -> List.of(
                c.tickets().find(first).orElseThrow().assignee(),
                c.tickets().find(second).orElseThrow().assignee()));
        assertThat(assignees).as("one ticket each").doesNotHaveDuplicates();
        AgentId one = assignees.get(0);
        AgentId other = assignees.get(1);
        AgentSession a =
                sessions.stream().filter(s -> s.agent().equals(one)).findFirst().orElseThrow();
        AgentSession b = sessions.stream()
                .filter(s -> s.agent().equals(other))
                .findFirst()
                .orElseThrow();
        assertThat(a.startedAt().isBefore(b.endedAt()) && b.startedAt().isBefore(a.endedAt()))
                .as("the two developer sessions overlap: %s and %s", a, b)
                .isTrue();
        List<String> worktrees = office.query(c -> List.of(
                c.workshop().workspace(m, one).orElseThrow().path(),
                c.workshop().workspace(m, other).orElseThrow().path()));
        assertThat(worktrees).as("each in its own worktree").doesNotHaveDuplicates();
        AgentId reviewer = office.query(
                c -> c.tickets().find(first).orElseThrow().reviews().getLast().reviewer());
        AgentId producer =
                office.query(c -> c.tickets().find(first).orElseThrow().assignee());
        assertThat(reviewer).isNotEqualTo(producer);
        api().body("{\"reason\":\"done\"}")
                .post("/api/agents/" + ken + "/dismiss")
                .then()
                .statusCode(200);
    }
}
