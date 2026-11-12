package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.AgentStatus;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.Retro;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.app.Briefs;
import dev.floor42.server.app.OfficeService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The end-of-mission retrospective (ADR-0020), with the fake Claude Code: once the director closes the mission, those
 * who worked on it speak in turn, the chair concludes with lessons, and only the lessons the director accepts reach
 * later briefs, for the role they name.
 */
@QuarkusTest
@TestProfile(RetroApiTest.RetroOn.class)
class RetroApiTest extends ApiTestBase {

    /** Retrospectives are off in other tests: they would add sessions to the missions those tests check. */
    public static class RetroOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floor42.retro.enabled", "true");
        }
    }

    @Inject
    OfficeService office;

    @Inject
    dev.floor42.server.app.RetroConvening convening;

    @Inject
    dev.floor42.server.app.MissionScheduler scheduler;

    @Test
    void thoseWhoWorkedMeetThenTheDirectorKeepsTheLessons() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = new MissionId(api().body("{\"title\":\"Retro\",\"objective\":\"Describe this repository\","
                        + "\"repository\":\"" + repo.toString().replace("\\", "\\\\") + "\"}")
                .post("/api/missions")
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await("the reviewed ticket", Duration.ofSeconds(60), () -> {
            office.query(c -> c.desk().of(m).stream()
                            .filter(q -> q.status() == QuestionStatus.OPEN)
                            .map(q -> q.id().value())
                            .toList())
                    .forEach(q -> api().body("{\"answer\":\"ALLOW\"}").post("/api/questions/" + q + "/answer"));
            return office.query(c -> c.tickets().of(m).getFirst().status()) == TicketStatus.DONE;
        });
        api().body("{\"reason\":\"delivered\"}")
                .post("/api/missions/" + m.value() + "/complete")
                .then()
                .statusCode(200);

        await(
                "the concluded retrospective",
                Duration.ofSeconds(60),
                () -> office.query(c -> c.retros().find(m).map(Retro::concluded).orElse(false)));

        Retro r = office.query(c -> c.retros().find(m).orElseThrow());
        // Chris produced, Darius reviewed: both speak; the reviewer chairs when the planner did not take part.
        assertThat(r.participants()).extracting(AgentId::value).containsExactly("chris", "darius");
        assertThat(r.chair().value()).isEqualTo("darius");
        assertThat(r.facts()).contains("Sessions recorded by Floor42: 2").contains("cost estimated by Claude Code");
        assertThat(r.contributions()).hasSize(2).allSatisfy(x -> {
            assertThat(x.wentWell()).containsExactly("The ticket was validated on its first review");
            assertThat(x.wentBadly()).containsExactly("Every Bash command waited for the director");
        });
        assertThat(r.conclusions()).hasSize(2);
        // Each turn is a session of its own, on the retrospective's ticket, with the light model.
        List<AgentSession> retroSessions = office.query(c -> c.sessions().of(m).stream()
                .filter(s -> s.ticket().equals(r.ticket()))
                .toList());
        assertThat(retroSessions).extracting(s -> s.agent().value()).containsExactly("chris", "darius", "darius");
        assertThat(retroSessions).allSatisfy(s -> assertThat(s.model()).isEqualTo("claude-haiku-5-5"));
        AgentStatus chris = office.query(c -> c.queries().statusOf(new AgentId("chris")));
        assertThat(chris).isNotEqualTo(AgentStatus.IN_RETRO);

        List<Lesson> proposed = office.query(c ->
                c.retros().lessons().stream().filter(l -> l.mission().equals(m)).toList());
        assertThat(proposed)
                .extracting(Lesson::text)
                .containsExactly("List every file you changed in the report", "Say what you could not verify");
        Lesson forDevelopers = proposed.getFirst();
        Lesson forAll = proposed.get(1);
        assertThat(forDevelopers.audience()).isEqualTo(dev.floor42.domain.WorkRole.DEVELOPER);
        assertThat(forAll.audience()).isNull();

        // Proposed is not binding: no brief carries it.
        assertThat(producerBrief(m)).doesNotContain("List every file you changed");

        api().body("{\"text\":\"List every file you changed, with its path, in the report\"}")
                .post("/api/lessons/" + forDevelopers.id() + "/accept")
                .then()
                .statusCode(200);
        api().body("{\"reason\":\"too vague\"}")
                .post("/api/lessons/" + forAll.id() + "/reject")
                .then()
                .statusCode(200);
        given().contentType("application/json")
                .body("{}")
                .post("/api/lessons/" + forAll.id() + "/accept")
                .then()
                .statusCode(401);

        assertThat(producerBrief(m)).contains("List every file you changed, with its path, in the report");
        assertThat(reviewerBrief(m)).doesNotContain("List every file you changed");
        assertThat(producerBrief(m)).doesNotContain("Say what you could not verify");

        api().get("/api/missions/" + m.value())
                .then()
                .statusCode(200)
                .body("retro.chair", org.hamcrest.Matchers.equalTo("darius"))
                .body("retro.lessons.status", org.hamcrest.Matchers.contains("ACCEPTED", "REJECTED"));
    }

    /**
     * A real run cancelled a mission mid-work: a review session went on and completed its ticket two seconds later, and
     * three retrospective sessions ran and proposed lessons. Cancelling now stops every session of the mission, and
     * no retrospective is opened for it at all.
     */
    @Test
    void aCancelledMissionStopsItsSessionsAndSpendsNothingMore() throws Exception {
        Path repo = LiveMissionTest.gitRepository();
        MissionId m = new MissionId(api().body("{\"title\":\"Cancelled\",\"objective\":\"Describe this repository"
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

        api().body("{\"reason\":\"the director changed his mind\"}")
                .post("/api/missions/" + m.value() + "/cancel")
                .then()
                .statusCode(200);

        await(
                "every session of the mission stopped",
                Duration.ofSeconds(30),
                () -> office.query(c -> c.sessions().of(m).stream().noneMatch(AgentSession::isOpen)));
        // The retrospective opens on a completed mission only: a cancelled one opens none, and leaves no ticket open.
        dev.floor42.server.app.SchedulerQuiet.await(scheduler);
        Optional<Retro> retro = office.query(c -> c.retros().find(m));
        assertThat(retro).as("no retrospective").isEmpty();
        List<Ticket> all = office.query(c -> c.tickets().of(m));
        assertThat(all)
                .as("every ticket closed with the mission, none left for a retrospective")
                .allSatisfy(t -> assertThat(t.status()).isEqualTo(TicketStatus.CANCELLED));
        assertThat(convening.refusal(m)).contains("MISSION_CANCELLED");
        List<Lesson> lessons = office.query(c ->
                c.retros().lessons().stream().filter(l -> l.mission().equals(m)).toList());
        assertThat(lessons).isEmpty();
        dev.floor42.domain.MissionStatus status =
                office.query(c -> c.missions().find(m).orElseThrow().status());
        assertThat(status).isEqualTo(dev.floor42.domain.MissionStatus.CANCELLED);
    }

    private String producerBrief(MissionId m) {
        return office.query(c -> {
            Ticket t = c.tickets().of(m).getFirst();
            return Briefs.producer(
                    c,
                    c.missions().find(m).orElseThrow(),
                    t,
                    c.staff().find(new AgentId("chris")).orElseThrow());
        });
    }

    private String reviewerBrief(MissionId m) {
        return office.query(c -> {
            Ticket t = c.tickets().of(m).getFirst();
            return Briefs.reviewer(
                    c,
                    c.missions().find(m).orElseThrow(),
                    t,
                    c.staff().find(new AgentId("darius")).orElseThrow());
        });
    }
}
