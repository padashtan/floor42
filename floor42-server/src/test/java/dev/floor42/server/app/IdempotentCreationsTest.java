package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.MissionId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** A key is remembered for {@link IdempotentCreations#KEPT} from the end of its creation, not from its start. */
class IdempotentCreationsTest {

    /** A clock the test moves. */
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

    @Test
    void aKeyIsKeptFromTheEndOfItsCreation() {
        Moving clock = new Moving();
        IdempotentCreations creations = new IdempotentCreations(clock);
        // A slow creation: it ends 15 minutes after it began.
        IdempotentCreations.Outcome first = creations.create("k", "same", () -> {
            clock.now = clock.now.plus(Duration.ofMinutes(15));
            return new MissionId("M-1");
        });
        assertThat(first.replayed()).isFalse();

        clock.now = clock.now.plus(IdempotentCreations.KEPT.minusMinutes(1));
        IdempotentCreations.Outcome again = creations.create("k", "same", () -> new MissionId("M-2"));
        assertThat(again.replayed()).isTrue();
        assertThat(again.mission()).isEqualTo(new MissionId("M-1"));

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(creations.create("k", "same", () -> new MissionId("M-3")).mission())
                .as("forgotten once kept long enough")
                .isEqualTo(new MissionId("M-3"));
    }
}
