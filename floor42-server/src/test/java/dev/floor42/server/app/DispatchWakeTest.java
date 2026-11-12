package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import dev.floor42.server.app.OfficeServiceTest.MemoryJournal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The dispatcher looks again when a hold ends, and a failing runner never keeps the other missions asleep. */
class DispatchWakeTest {

    static final MissionId M1 = new MissionId("M-1");
    static final MissionId M2 = new MissionId("M-2");
    static final AgentId CHRIS = new AgentId("chris");
    static final Task TASK = new Task(Work.PRODUCE, new TicketId("T-1"), CHRIS);

    final OfficeService office = new OfficeService(new MemoryJournal(), Clock.systemUTC());
    final List<String> forks = new ArrayList<>();
    final List<MissionId> nudged = new ArrayList<>();

    /** Dispatcher over doubles; the scheduler runs each fork at once, on the caller's thread. */
    final class Rig extends Dispatcher {
        Rig(TaskRunners runners) {
            super(
                    new DispatchBoard(null, null, null, null, null, null) {
                        boolean given;

                        @Override
                        List<MissionId> inProgress() {
                            return List.of(M1, M2);
                        }

                        @Override
                        void requeueLeftBehind(MissionId m) {}

                        @Override
                        Optional<Task> pick(MissionId m) {
                            if (given || !m.equals(M1)) return Optional.empty();
                            given = true;
                            return Optional.of(TASK);
                        }

                        @Override
                        void free(MissionId m, Task task) {}
                    },
                    runners,
                    null,
                    new MissionScheduler() {
                        @Override
                        public boolean fork(String name, Runnable work) {
                            forks.add(name);
                            work.run();
                            return true;
                        }
                    },
                    new MissionSigns(office, null) {
                        @Override
                        void mark(MissionId m) {}
                    },
                    null,
                    true);
        }

        @Override
        boolean blocked(MissionId m, boolean pastApproval) {
            return false;
        }

        @Override
        void nudge(MissionId m) {
            nudged.add(m);
        }
    }

    TaskRunners failing(RetryDesk retry) {
        return new TaskRunners(
                List.of(new TaskRunner() {
                    @Override
                    public Work work() {
                        return Work.PRODUCE;
                    }

                    @Override
                    public void run(MissionId m, Task task) {
                        throw new IllegalStateException("boom");
                    }
                }),
                office,
                retry);
    }

    @Test
    void otherMissionsAreWokenEvenWhenTheRunnerThrowsOutOfTheRunner() {
        TaskRunners runners = new TaskRunners(List.of(), office, null) {
            @Override
            boolean run(MissionId m, Task task) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> new Rig(runners).produce(M1)).isInstanceOf(IllegalStateException.class);

        assertThat(forks).contains("wake-M-2");
    }

    @Test
    void aRetryRefusedBecauseTheMissionClosedStillLetsTheRunnerReturn() {
        RetryDesk closed = new RetryDesk(office) {
            @Override
            void ask(MissionId m, TicketId t, AgentId agent, String why) {
                throw new DomainException("MISSION_CLOSED", "closed");
            }
        };

        assertThat(failing(closed).run(M1, TASK)).isTrue();
        new Rig(failing(closed)).produce(M1);

        assertThat(forks).contains("wake-M-2");
    }

    @Test
    void endingTheWorkshopHoldNudgesTheMission() {
        AgentReservations reservations =
                new AgentReservations(new DispatchLock(), office, null, new Rig(null), Duration.ofMinutes(1));

        var held = reservations.holdForWorkshop(M1, CHRIS).orElseThrow();
        assertThat(nudged).isEmpty();
        reservations.releaseWorkshop(M1, held);

        assertThat(nudged).containsExactly(M1);
    }
}
