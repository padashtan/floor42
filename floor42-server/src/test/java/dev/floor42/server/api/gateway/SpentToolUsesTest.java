package dev.floor42.server.api.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpentToolUsesTest {

    private static final java.util.function.BooleanSupplier DONE = () -> false;

    @Test
    void aSpentIdOfAFinishedActionIsForgottenByAgeAndByTheBound() {
        long[] now = {0};
        SpentToolUses spent = new SpentToolUses(Duration.ofMinutes(10), 3, () -> now[0]);

        assertThat(spent.spend("a", DONE)).isTrue();
        assertThat(spent.spend("a", DONE)).isFalse();

        now[0] = Duration.ofMinutes(11).toMillis();
        assertThat(spent.spend("a", DONE)).as("expired by age, action finished").isTrue();

        assertThat(spent.spend("b", DONE)).isTrue();
        assertThat(spent.spend("c", DONE)).isTrue();
        assertThat(spent.spend("d", DONE)).isTrue();
        assertThat(spent.spend("a", DONE))
                .as("bound of 3 evicted the eldest, action finished")
                .isTrue();
    }

    @Test
    void anIdWhoseActionIsStillAuthorizedIsNeverForgotten() {
        long[] now = {0};
        SpentToolUses spent = new SpentToolUses(Duration.ofMinutes(10), 2, () -> now[0]);
        Set<String> live = new HashSet<>(Set.of("a", "b", "c"));

        assertThat(spent.spend("a", () -> live.contains("a"))).isTrue();
        now[0] = Duration.ofHours(2).toMillis();
        assertThat(spent.spend("a", () -> live.contains("a")))
                .as("replay after the age")
                .isFalse();

        assertThat(spent.spend("b", () -> live.contains("b"))).isTrue();
        assertThat(spent.spend("c", () -> live.contains("c"))).isTrue();
        assertThat(spent.spend("a", () -> live.contains("a")))
                .as("replay after the bound")
                .isFalse();

        live.remove("a");
        now[0] = Duration.ofHours(4).toMillis();
        assertThat(spent.spend("a", () -> live.contains("a")))
                .as("finished action: forgotten")
                .isTrue();
    }
}
