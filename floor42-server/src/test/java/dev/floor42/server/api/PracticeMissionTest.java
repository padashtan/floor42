package dev.floor42.server.api;

import static dev.floor42.server.api.LiveTestSupport.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import dev.floor42.domain.TicketStatus;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.SimulatedCrew;
import dev.floor42.server.app.StalledMissions;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The guided first mission (ADR-0045): a rehearsal on Floor42's sample repository that waits for the director at each
 * decision a real mission asks for (the cap, an agent's question, a write), writes only what was allowed, into the
 * sample's worktree, and records every event as simulated.
 */
@QuarkusTest
class PracticeMissionTest extends ApiTestBase {

    static final Duration WAIT = Duration.ofSeconds(10);
    static final AgentId CHRIS = new AgentId("chris");

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    @Inject
    SimulatedCrew crew;

    MissionId start(String objective) {
        String id = api().body("{\"title\":\"First mission\",\"objective\":\"" + objective + "\"}")
                .post("/api/demo/practice?paceMs=0")
                .then()
                .statusCode(202)
                .extract()
                .path("mission");
        return new MissionId(id);
    }

    Optional<String> openQuestion(MissionId m, boolean approval) {
        return office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN)
                .filter(q -> (q.kind() == QuestionKind.ACTION_APPROVAL) == approval)
                .map(q -> q.id().value())
                .findFirst());
    }

    void answer(String question, String choice) {
        api().body("{\"answer\":\"" + choice + "\"}")
                .post("/api/questions/" + question + "/answer")
                .then()
                .statusCode(200);
    }

    List<dev.floor42.domain.Action> actions(MissionId m) {
        return office.query(c -> c.actions().of(m));
    }

    List<dev.floor42.domain.Question> questions(MissionId m) {
        return office.query(c -> c.desk().of(m));
    }

    String state(MissionId m) {
        return api().get("/api/demo/practice/" + m.value())
                .then()
                .statusCode(200)
                .extract()
                .path("state");
    }

    MissionStatus status(MissionId m) {
        return office.query(c -> c.missions().find(m).orElseThrow().status());
    }

    List<TicketStatus> tickets(MissionId m) {
        return office.query(c -> c.tickets().of(m).stream().map(t -> t.status()).toList());
    }

    Path worktree(MissionId m) {
        String path =
                office.query(c -> c.workshop().workspace(m, CHRIS).orElseThrow().path());
        return Path.of(path);
    }

    @Test
    void theSampleIsFloor42sOwnThrowawayRepository() {
        String path =
                api().post("/api/demo/sample").then().statusCode(200).extract().path("path");
        Path sample = Path.of(path);
        assertThat(sample.toString()).contains("tutorial");
        assertThat(sample.resolve(".git")).isDirectory();
        assertThat(sample.resolve("README.md")).content().startsWith("# Sandbox project");
        String again =
                api().post("/api/demo/sample").then().statusCode(200).extract().path("path");
        assertThat(again).as("prepared once, then the same folder").isEqualTo(path);
    }

    @Test
    void theRehearsalWaitsForEachDecisionAndWritesOnlyWhatWasAllowed() throws Exception {
        MissionId m = start("Inventory the sample and leave notes");
        assertThat(status(m)).as("the plan waits on return").isEqualTo(MissionStatus.AWAITING_APPROVAL);
        assertThat(state(m)).isEqualTo("PLAYING");
        String sample = office.query(c -> c.missions().find(m).orElseThrow().workspace());
        assertThat(sample).contains("tutorial");

        api().post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(409)
                .body("code", equalTo("CAP_NEEDED"));
        api().body("{\"budgetUsd\":1}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);

        await("the agent's question", WAIT, () -> openQuestion(m, false).isPresent());
        // A fixed wait on purpose, for an absence: the rehearsal plays on its own raw thread (PracticeScenario), which
        // the MissionScheduler does not see, and while it waits for this answer it records nothing that could be
        // awaited instead. At pace 0, a session that did not wait would have recorded its actions well within 200 ms.
        Thread.sleep(200);
        assertThat(actions(m)).as("the session waits for the answer").isEmpty();
        answer(openQuestion(m, false).orElseThrow(), "Yes");

        await("the write to approve", WAIT, () -> openQuestion(m, true).isPresent());
        answer(openQuestion(m, true).orElseThrow(), "ALLOW");

        await("both tickets done", WAIT, () -> tickets(m).equals(List.of(TicketStatus.DONE, TicketStatus.DONE)));
        Path worktree = worktree(m);
        assertThat(worktree.resolve("notes.txt")).content().isEqualTo("hello");
        assertThat(worktree.resolve("summary.txt")).content().isEqualTo("done");
        assertThat(Path.of(sample).resolve("notes.txt"))
                .as("the sample itself is untouched")
                .doesNotExist();
        String findings = office.query(c -> c.tickets().of(m).getFirst().deliverable());
        assertThat(findings).contains("README.md, as read: # Sandbox project");

        List<EventEnvelope> events =
                journal.readAll().stream().filter(e -> m.equals(e.mission())).toList();
        assertThat(events).isNotEmpty().allMatch(e -> e.origin() == Origin.SIMULATED);

        Float usd = given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions/" + m.value() + "/costs")
                .then()
                .statusCode(200)
                .extract()
                .path("mission.total.usdEstimated");
        assertThat(usd).as("a simulated mission spends nothing").isEqualTo(0f);
        await("the script's end", WAIT, () -> state(m).equals("DONE"));
        Float sessionCost = given().header("Authorization", "Bearer " + token.value())
                .get("/api/missions/" + m.value())
                .then()
                .extract()
                .path("sessions[0].costUsdEstimated");
        assertThat(sessionCost)
                .as("the recording's estimate is not this session's cost")
                .isNull();
    }

    @Test
    void aDeniedWriteLeavesTheWorktreeClean() throws Exception {
        MissionId m = start("Inventory the sample, then I decide on the notes");
        api().body("{\"noCap\":true}")
                .post("/api/missions/" + m.value() + "/approve")
                .then()
                .statusCode(200);
        await("the agent's question", WAIT, () -> openQuestion(m, false).isPresent());
        answer(openQuestion(m, false).orElseThrow(), "No");
        await("the write to approve", WAIT, () -> openQuestion(m, true).isPresent());
        answer(openQuestion(m, true).orElseThrow(), "DENY");

        await("both tickets done", WAIT, () -> tickets(m).equals(List.of(TicketStatus.DONE, TicketStatus.DONE)));
        try (var files = Files.list(worktree(m))) {
            assertThat(files.map(p -> p.getFileName().toString())).doesNotContain("notes.txt", "summary.txt");
        }
        String findings = office.query(c -> c.tickets().of(m).getFirst().deliverable());
        assertThat(findings).doesNotContain("README.md, as read");
    }

    @Test
    void aRejectedPlanStopsTheRehearsal() throws Exception {
        MissionId m = start("Inventory the sample");
        api().body("{\"reason\":\"not now\"}")
                .post("/api/missions/" + m.value() + "/reject")
                .then()
                .statusCode(200);
        await("the script to stop", WAIT, () -> state(m).equals("STOPPED"));
        assertThat(questions(m)).as("no question after a rejection").isEmpty();
        assertThat(actions(m)).isEmpty();
    }

    @Test
    void aRehearsalWithoutPlanIsStoppedNeverDone() {
        String sample =
                api().post("/api/demo/sample").then().statusCode(200).extract().path("path");
        MissionId m = ((dev.floor42.domain.event.MissionEvent.MissionCreated) office.execute(c -> c.missions()
                                .createRehearsal(
                                        office.context(dev.floor42.domain.Actor.director(), Origin.SIMULATED),
                                        "No plan",
                                        "The plan failed",
                                        sample))
                        .getFirst()
                        .event())
                .mission();
        assertThat(state(m)).isEqualTo("STOPPED");
    }

    @Test
    void aReplayFileOfASimulatedSessionCarriesNoCost() {
        String sample =
                api().post("/api/demo/sample").then().statusCode(200).extract().path("path");
        StalledMissions.Stalled s = StalledMissions.of(office, crew, sample, Origin.SIMULATED);
        // An older demo journal: the recording's estimate was written on the simulated session's end.
        var ctx = office.context(dev.floor42.domain.Actor.system(), Origin.SIMULATED);
        office.execute(c -> c.sessions().start(ctx, "old-1", s.ticket(), CHRIS, "claude-haiku-5-5", "2.1.304"));
        office.execute(c -> c.sessions().end(ctx, "old-1", false, "completed", 3, 1000L, 100L, 0.0024));
        String id = s.mission().value();

        var preview = api().get("/api/missions/" + id + "/replay-export/preview")
                .then()
                .statusCode(200)
                .body("costs", equalTo(0))
                .extract();
        String html = api().body(java.util.Map.of("digest", preview.path("digest")))
                .post("/api/missions/" + id + "/replay-export")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        assertThat(html).doesNotContain("\"basis\":\"estimated\"").contains("simulated: no model was called");
    }

    @Test
    void onlyARehearsalHasAState() {
        String demo = api().post("/api/demo").then().statusCode(200).extract().path("mission");
        api().get("/api/demo/practice/" + demo).then().statusCode(404);
    }

    @Test
    void aRehearsalIsNeverNudged() {
        MissionId m = start("Inventory the sample");
        api().post("/api/missions/" + m.value() + "/nudge")
                .then()
                .statusCode(409)
                .body("code", equalTo("SIMULATED_MISSION"));
    }
}
