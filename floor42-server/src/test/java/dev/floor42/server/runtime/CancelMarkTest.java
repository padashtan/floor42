package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A cancelled mission's mark stops a process launched in the same instant, and expires after MARK_KEPT. */
class CancelMarkTest {

    static final class Moving extends Clock {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static Process longProcess() throws Exception {
        List<String> sleeper = System.getProperty("os.name").startsWith("Windows")
                ? List.of("ping", "-n", "30", "127.0.0.1")
                : List.of("sleep", "30");
        return new ProcessBuilder(sleeper)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Test
    void theMarkStopsALateLaunchThenExpires() throws Exception {
        Moving clock = new Moving();
        RunningSessions sessions = new RunningSessions(clock);
        MissionId m = new MissionId("M-1");
        AgentId chris = new AgentId("chris");
        sessions.closeMission(m, "cancelled");

        Process late = longProcess();
        sessions.started(chris, m, late);
        assertThat(late.isAlive())
                .as("launched in the instant of the cancellation")
                .isFalse();
        sessions.ended(chris, late);

        clock.now = clock.now.plus(RunningSessions.MARK_KEPT).plusSeconds(1);
        Process next = longProcess();
        try {
            sessions.started(chris, m, next);
            assertThat(next.isAlive()).as("the mark has expired").isTrue();
        } finally {
            next.destroyForcibly();
        }
    }
}
