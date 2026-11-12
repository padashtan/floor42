package dev.floor42.server.app;

import static dev.floor42.server.app.SimulatedCrew.CHRIS;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Actor;
import dev.floor42.domain.CommandContext;
import dev.floor42.domain.Origin;
import dev.floor42.server.app.StalledMissions.Stalled;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.RuntimeEvent;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The hold at the hook (ADR-0047), on its own: a real mission built by hand, an inner governor that allows everything,
 * nothing started. A call is refused during the meeting, a pending call answered during it too; a call it let run
 * counts as running while it shows signs of life, and no longer once silent past the bound.
 */
@QuarkusTest
@TestProfile(StallTest.DispatcherOff.class)
class MeetingHoldTest {

    @Inject
    OfficeService office;

    @Inject
    SimulatedCrew crew;

    @Inject
    SampleRepository sample;

    @Inject
    ObjectMapper json;

    final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void closeWhatWasOpened() {
        cleanup.forEach(Runnable::run);
        cleanup.clear();
    }

    /** Allows every call, at once and when awaited: what the mission's policy would say for an allowed tool. */
    static final class AllowAll implements Governor {
        @Override
        public Verdict decide(ToolRequested call) {
            return Verdict.ALLOW;
        }

        @Override
        public Verdict awaitDecision(ToolRequested call, Duration patience) {
            return Verdict.ALLOW;
        }

        @Override
        public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
            return null;
        }

        @Override
        public void completed(ToolCompleted result) {}
    }

    MeetingHold hold(Duration longestSilence) {
        return hold(longestSilence, Duration.ofMinutes(30));
    }

    MeetingHold hold(Duration longestSilence, Duration longestLongSilence) {
        Stalled s = StalledMissions.of(office, crew, sample.prepare().toString(), Origin.REAL);
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        cleanup.add(() -> office.execute(c -> c.missions().cancel(director, s.mission(), "test over")));
        SessionSpec spec = office.query(c -> SessionGate.spec(
                c,
                s.mission(),
                s.ticket(),
                c.staff().find(CHRIS).orElseThrow(),
                Path.of(sample.location().toString()),
                "brief",
                List.of()));
        return new MeetingHold(office, spec, new AllowAll(), longestSilence, longestLongSilence);
    }

    void callMeeting(MeetingHold h) {
        CommandContext director = office.context(Actor.director(), Origin.REAL);
        office.execute(c -> c.meetings().call(director, h.spec().mission(), List.of(CHRIS), null));
        String id = office.query(
                c -> c.meetings().openOn(h.spec().mission()).orElseThrow().id());
        cleanup.add(0, () -> office.execute(c -> c.meetings().close(director, id)));
    }

    static ToolRequested call(String id) {
        return new ToolRequested(id, "Read", json().createObjectNode().put("file_path", "README.md"));
    }

    static ObjectMapper json() {
        return new ObjectMapper();
    }

    @Test
    void aCallDuringTheMeetingIsRefusedWithTheMeetingAsTheReason() {
        MeetingHold h = hold(Duration.ofMinutes(2));
        assertThat(h.decide(call("t1"))).isEqualTo(Verdict.ALLOW);
        assertThat(h.refusedForMeeting())
                .as("nothing refused before the meeting")
                .isFalse();

        callMeeting(h);

        assertThat(h.decide(call("t2"))).isEqualTo(Verdict.DENY);
        assertThat(h.explain("t2")).isEqualTo(MeetingHold.REASON);
        assertThat(h.refusedForMeeting()).isTrue();
    }

    @Test
    void aPendingCallAnsweredDuringTheMeetingIsRefused() {
        MeetingHold h = hold(Duration.ofMinutes(2));
        callMeeting(h);

        assertThat(h.awaitDecision(call("t3"), Duration.ofSeconds(1)))
                .as("the director allowed it, but the meeting is on")
                .isEqualTo(Verdict.DENY);
        assertThat(h.toolRunning()).as("it never ran").isFalse();
        assertThat(h.refusedForMeeting()).isTrue();
    }

    /** No heartbeat is proven for a subagent or an MCP tool (ADR-0047, amendment 3): silence is not death for them. */
    @Test
    void aSubagentOrMcpCallSilentPastTheShortBoundStillCountsAsRunning() throws InterruptedException {
        MeetingHold h = hold(Duration.ofMillis(100), Duration.ofMinutes(30));
        h.decide(new ToolRequested("sub", "Task", json().createObjectNode()));
        h.decide(new ToolRequested("mcp", "mcp__docs__search", json().createObjectNode()));
        h.decide(call("plain"));
        Thread.sleep(300);
        assertThat(h.toolRunning())
                .as("the two long tools may still be working")
                .isTrue();

        h.completed(new ToolCompleted("sub", false, false, "ok"));
        h.completed(new ToolCompleted("mcp", false, false, "ok"));
        assertThat(h.toolRunning())
                .as("the plain call is silent past its short bound")
                .isFalse();
    }

    @Test
    void anAllowedCallRunsUntilItsResultOrWhileItShowsSignsOfLife() throws InterruptedException {
        MeetingHold h = hold(Duration.ofMillis(300));
        h.decide(call("t4"));
        assertThat(h.toolRunning()).isTrue();
        h.completed(new ToolCompleted("t4", false, false, "ok"));
        assertThat(h.toolRunning()).as("its result came back").isFalse();

        h.decide(call("t5"));
        for (int i = 0; i < 4; i++) {
            Thread.sleep(150);
            h.observed(new RuntimeEvent.ToolProgress("t5", "Read", i));
        }
        assertThat(h.toolRunning())
                .as("its heartbeat keeps it running past the bound")
                .isTrue();
        Thread.sleep(500);
        assertThat(h.toolRunning())
                .as("silent past the bound: a result that never came")
                .isFalse();
    }
}
