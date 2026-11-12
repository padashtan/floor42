package dev.floor42.server.app;

import static dev.floor42.server.app.SimulatedCrew.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Retro;
import dev.floor42.domain.event.StaffingEvent.StaffingAdvised;
import dev.floor42.server.app.StalledMissions.Stalled;
import dev.floor42.server.journal.Journal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What follows a director's meeting (ADR-0047), with live missions disabled: nothing starts by itself, so only the
 * start-up subscription of {@link DirectorMeetings} (made whatever the setting) can free an attendee whose mission
 * ended. Its own daemon: no other class shares the missions it builds.
 */
@QuarkusTest
@TestProfile(MeetingFollowUpsTest.Own.class)
class MeetingFollowUpsTest {

    /** Live missions off, as in the tests that drive the runtime by hand; a profile of its own, so a fresh daemon. */
    public static class Own implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("floor42.live-missions.enabled", "false");
        }
    }

    @Inject
    OfficeService office;

    @Inject
    SimulatedCrew crew;

    @Inject
    SampleRepository sample;

    @Inject
    DirectorMeetings meetings;

    @Inject
    LiveMissions live;

    @Inject
    Retrospectives retros;

    @Inject
    Staffing staffing;

    @Inject
    Journal journal;

    final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void closeWhatWasOpened() {
        cleanup.forEach(Runnable::run);
        cleanup.clear();
    }

    CommandContext director() {
        return office.context(Actor.director(), Origin.REAL);
    }

    Stalled mission() {
        Stalled s = StalledMissions.of(office, crew, sample.prepare().toString(), Origin.REAL);
        cleanup.add(() -> {
            if (!ended(s.mission())) office.execute(c -> c.missions().cancel(director(), s.mission(), "test over"));
        });
        return s;
    }

    boolean ended(MissionId m) {
        return office.query(c -> c.missions().find(m).orElseThrow().status().isTerminal());
    }

    String callChris(Stalled s) {
        String id = meetings.call(s.mission(), List.of(CHRIS), null);
        cleanup.add(0, () -> {
            if (office.query(c -> c.meetings().find(id).orElseThrow().open())) meetings.close(id);
        });
        return id;
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) throw new AssertionError("Timed out waiting for: " + what);
            Thread.sleep(20);
        }
    }

    /** The subscription made at start-up frees the attendees of a mission that ends: dispatcher or not. */
    @Test
    void aMissionEndingDuringTheMeetingFreesItsAttendeesWhateverTheLiveSetting() throws Exception {
        Stalled s = mission();
        String meeting = callChris(s);
        assertThat(meetings.holds(meeting, CHRIS))
                .as("held while the meeting is open")
                .isTrue();
        assertThat(live.reserve(CHRIS)).isEmpty();

        office.execute(c -> c.missions().cancel(director(), s.mission(), "ended during the meeting"));

        await("the attendee freed by the mission's end", () -> !meetings.holds(meeting, CHRIS));
        var free = live.reserve(CHRIS);
        assertThat(free)
                .as("free for other work, the meeting still open in the journal")
                .isPresent();
        live.release(free.get());
    }

    /** F4: a wait that ends on the director's meeting says so, instead of "busy elsewhere". */
    @Test
    void aWaitEndedByTheMeetingNamesTheMeeting() {
        Stalled s = mission();
        callChris(s);

        assertThatThrownBy(() -> live.await(CHRIS, Duration.ofMillis(100)))
                .isInstanceOf(AgentReservations.ReservationTimeout.class)
                .hasMessageContaining("stayed at the director's meeting")
                .hasMessageNotContaining("busy elsewhere");
    }

    /** A retrospective turn the gate refused for the meeting is recorded with that reason, not another. */
    @Test
    void aRetrospectiveTurnRefusedForTheMeetingIsRecordedWithThatReason() {
        Stalled s = mission();
        String session = "ret-" + s.ticket().value();
        StalledMissions.openSession(office, s, Origin.REAL, session, Instant.now());
        CommandContext system = office.context(Actor.system(), Origin.REAL);
        office.execute(c -> c.sessions().end(system, session, false, "completed", 0, null, null, null));
        office.execute(c -> c.tickets().cancel(director(), s.ticket(), "not needed"));
        office.execute(c -> c.missions().complete(director(), s.mission(), "over"));
        office.execute(c -> c.retros().open(system, s.mission(), CHRIS, List.of(CHRIS), "facts"));

        retros.settle(s.mission(), CHRIS, false, new Unheld.NotStarted(Unheld.Reason.AT_THE_MEETING));

        Retro r = office.query(c -> c.retros().find(s.mission()).orElseThrow());
        assertThat(r.contributions()).singleElement().satisfies(c -> {
            assertThat(c.skipped()).isEqualTo(Unheld.Reason.AT_THE_MEETING.why());
            assertThat(c.skipped()).doesNotContain("simulated");
        });

        // The chair, who cannot conclude either: the conclusion's reason says the meeting too (and it frees chris).
        retros.settle(s.mission(), CHRIS, true, new Unheld.NotStarted(Unheld.Reason.AT_THE_MEETING));
        Retro done = office.query(c -> c.retros().find(s.mission()).orElseThrow());
        assertThat(done.concluded()).isTrue();
        assertThat(done.reason()).contains(Unheld.Reason.AT_THE_MEETING.why());
    }

    /** A staffing review the gate refused for the meeting is closed with that reason, and the planner is free. */
    @Test
    void aStaffingReviewRefusedForTheMeetingIsClosedWithThatReason() {
        Stalled s = mission();
        var planner = Staffing.planner();
        office.execute(c -> c.staff().requestStaffingReview(director(), s.mission(), planner));

        staffing.settle(
                s.mission(),
                planner,
                new Staffing.Facts("facts", "estimate"),
                new Unheld.NotStarted(Unheld.Reason.AT_THE_MEETING));

        boolean advised = office.query(
                c -> c.staff().staffingReview(s.mission()).orElseThrow().advised());
        assertThat(advised).isTrue();
        String advice = journal.readAll().stream()
                .map(e -> e.event())
                .filter(StaffingAdvised.class::isInstance)
                .map(e -> (StaffingAdvised) e)
                .filter(e -> e.mission().equals(s.mission()))
                .findFirst()
                .orElseThrow()
                .advice();
        assertThat(advice).contains(Unheld.Reason.AT_THE_MEETING.why()).doesNotContain("simulated");
    }
}
