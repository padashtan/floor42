package dev.floor42.server.app;

import static dev.floor42.server.app.SimulatedCrew.CHRIS;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.StalledMissions.Stalled;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import dev.floor42.server.runtime.CountingRuntime;
import dev.floor42.server.security.SessionToken;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A rehearsal (ADR-0045) never reaches a live Claude Code. Each barrier is tested on its own, next to a real mission in
 * the same state that does get through it (the positive control): the director's nudge, the dispatcher's pick, the
 * session gate, and the gate's unheld path used by retrospectives and staffing reviews. Nothing starts by itself here.
 */
@QuarkusTest
@TestProfile(StallTest.DispatcherOff.class)
class RehearsalDispatchTest {

    @Inject
    OfficeService office;

    @Inject
    SimulatedCrew crew;

    @Inject
    SampleRepository sample;

    @Inject
    DispatchBoard board;

    @Inject
    SessionGate gate;

    @Inject
    Progress progress;

    @Inject
    dev.floor42.domain.workflow.MissionInterpreter workflow;

    @Inject
    SessionToken token;

    @Inject
    ObjectMapper json;

    CountingRuntime runtime;

    @BeforeEach
    void countLaunches() {
        runtime = new CountingRuntime(json);
        QuarkusMock.installMockForType(runtime, ClaudeCodeRuntime.class);
    }

    /** What each test opened, closed after it: other tests of this profile share the company (StallTest). */
    final List<Runnable> cleanup = new java.util.ArrayList<>();

    Stalled stalled(Origin origin) {
        Stalled s = StalledMissions.of(office, crew, sample.prepare().toString(), origin);
        CommandContext director = office.context(Actor.director(), origin);
        cleanup.add(() -> office.execute(c -> c.missions().cancel(director, s.mission(), "test over")));
        return s;
    }

    void openSession(Stalled s, Origin origin, String id, Instant at) {
        StalledMissions.openSession(office, s, origin, id, at);
        CommandContext system = office.context(Actor.system(), origin);
        cleanup.add(
                0, () -> office.execute(c -> c.sessions().end(system, id, false, "completed", 0, null, null, null)));
    }

    @AfterEach
    void closeWhatWasOpened() {
        cleanup.forEach(Runnable::run);
        cleanup.clear();
    }

    SessionSpec spec(Stalled s) {
        return office.query(c -> SessionGate.spec(
                c,
                s.mission(),
                s.ticket(),
                c.staff().find(CHRIS).orElseThrow(),
                Path.of(sample.location().toString()),
                "brief",
                List.of()));
    }

    @Test
    void theNudgeRefusesARehearsalOnly() {
        Stalled rehearsal = stalled(Origin.SIMULATED);
        Stalled real = stalled(Origin.REAL);
        given().header("Authorization", "Bearer " + token.value())
                .post("/api/missions/" + rehearsal.mission().value() + "/nudge")
                .then()
                .statusCode(409)
                .body("code", equalTo("SIMULATED_MISSION"));
        given().header("Authorization", "Bearer " + token.value())
                .post("/api/missions/" + real.mission().value() + "/nudge")
                .then()
                .statusCode(200);
    }

    @Test
    void theDispatcherPicksTheRealMissionAndNeverTheRehearsal() {
        Stalled real = stalled(Origin.REAL);
        Optional<Task> picked = board.pick(real.mission());
        assertThat(picked).as("positive control: the real ticket is resumed").isPresent();
        board.free(real.mission(), picked.get());

        Stalled rehearsal = stalled(Origin.SIMULATED);
        Task wouldRun = office.query(c -> new TaskSelection(c, rehearsal.mission(), null, workflow)
                .next(java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));
        assertThat(wouldRun)
                .as("without the origin rule, the same state would be resumed")
                .isNotNull();
        assertThat(board.pick(rehearsal.mission())).isEmpty();
    }

    @Test
    void theSessionGateStartsTheRealSessionAndNeverTheRehearsal() {
        Stalled real = stalled(Origin.REAL);
        assertThatThrownBy(() -> gate.report(spec(real))).hasMessageContaining("No live session in this test");
        assertThat(runtime.launches)
                .as("positive control: the real session reaches the runtime")
                .hasValue(1);

        assertThat(gate.report(spec(stalled(Origin.SIMULATED)))).isEmpty();
        assertThat(runtime.launches)
                .as("no Claude Code process for a rehearsal")
                .hasValue(1);
    }

    @Test
    void retrospectivesAndStaffingReviewsGoThroughTheSameRule() {
        Stalled real = stalled(Origin.REAL);
        assertThatThrownBy(() -> gate.runUnheld(spec(real))).hasMessageContaining("No live session in this test");
        assertThat(runtime.launches).hasValue(1);

        assertThat(gate.runUnheld(spec(stalled(Origin.SIMULATED))))
                .isEqualTo(new Unheld.NotStarted(Unheld.Reason.SIMULATED_MISSION));
        assertThat(runtime.launches).hasValue(1);
    }

    /** ADR-0047: an attendee of the director's meeting gets no retrospective turn nor staffing review, said as such. */
    @Test
    void aRetrospectiveOrStaffingSessionIsRefusedToAnAttendeeWithTheTrueReason() {
        Stalled real = stalled(Origin.REAL);
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        office.execute(c -> c.meetings().call(director, real.mission(), List.of(CHRIS), null));
        String meeting = office.query(
                c -> c.meetings().openOn(real.mission()).orElseThrow().id());
        cleanup.add(0, () -> office.execute(c -> c.meetings().close(director, meeting)));

        assertThat(gate.runUnheld(spec(real))).isEqualTo(new Unheld.NotStarted(Unheld.Reason.AT_THE_MEETING));
        assertThat(Unheld.Reason.AT_THE_MEETING.why()).doesNotContain("simulated");
        assertThat(runtime.launches)
                .as("no Claude Code process for an attendee")
                .hasValue(0);
    }

    @Test
    void lookAgainIsOfferedOnASilentRealSessionOnly() {
        Instant old = Instant.now().minus(Duration.ofMinutes(Progress.SILENT_MINUTES + 5));
        Stalled real = stalled(Origin.REAL);
        openSession(real, Origin.REAL, "silent-real", old);
        Stalled rehearsal = stalled(Origin.SIMULATED);
        openSession(rehearsal, Origin.SIMULATED, "silent-sim", old);

        Map<String, List<String>> kinds = new java.util.HashMap<>();
        for (Progress.MissionProgress p : progress.all()) {
            kinds.put(p.mission(), p.running().stream().map(Progress.Item::kind).toList());
        }
        assertThat(kinds.get(real.mission().value())).containsExactly("SILENT");
        assertThat(kinds.get(rehearsal.mission().value())).containsExactly("SESSION");
    }
}
