package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.event.DomainEvent;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.MissionEvent.MissionCancelled;
import dev.floor42.domain.event.TicketEvent.TicketCancelled;
import dev.floor42.server.runtime.RunningSessions;
import dev.floor42.server.runtime.RunningSessionsAccess;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A mission cancelled with its tickets stops its sessions once, with the mission's note, never the ticket's. */
class CancelledMissionsTest {

    static final MissionId M = new MissionId("M-1");
    static final TicketId T = new TicketId("T-1");

    @jakarta.enterprise.inject.Vetoed
    static final class Spy extends CancelledMissions {
        final List<TicketId> ticketStops = new ArrayList<>();

        Spy(MissionScheduler scheduler, RunningSessions sessions) {
            super(null, scheduler, sessions);
        }

        @Override
        void stop(MissionId m, TicketId ticket) {
            ticketStops.add(ticket);
        }
    }

    private static EventEnvelope real(DomainEvent e) {
        return new EventEnvelope("e-" + e.hashCode(), Instant.now(), Origin.REAL, Actor.director(), M, null, null, e);
    }

    private static Process longProcess() throws Exception {
        List<String> sleeper = System.getProperty("os.name").startsWith("Windows")
                ? List.of("ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");
        return new ProcessBuilder(sleeper)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Test
    void theMissionsNoteWinsOverItsTicketsNote() throws Exception {
        MissionScheduler scheduler = new MissionScheduler();
        RunningSessions sessions = new RunningSessions();
        Spy spy = new Spy(scheduler, sessions);
        AgentId chris = new AgentId("chris");
        Process p = longProcess();
        RunningSessionsAccess.started(sessions, chris, M, p);

        spy.react(List.of(real(new TicketCancelled(T, "mission cancelled")), real(new MissionCancelled(M, "stop"))));

        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        SchedulerQuiet.await(scheduler);
        assertThat(RunningSessionsAccess.ended(sessions, chris, p)).isEqualTo(CancelledMissions.REASON);
        assertThat(spy.ticketStops)
                .as("no per-ticket stop in a mission's cancellation")
                .isEmpty();
    }

    @Test
    void aTicketCancelledAloneStillStopsItsSession() throws Exception {
        MissionScheduler scheduler = new MissionScheduler();
        Spy spy = new Spy(scheduler, new RunningSessions());

        spy.react(List.of(real(new TicketCancelled(T, "not needed"))));
        SchedulerQuiet.await(scheduler);

        assertThat(spy.ticketStops).containsExactly(T);
    }
}
