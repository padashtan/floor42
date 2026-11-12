package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An allow turned into a refusal by the last check is counted as a deny, explained, and told to the governor. */
class LateRefusalTest {

    static final class Allowing implements Governor {
        final List<ToolCompleted> completed = new ArrayList<>();
        String violation = "x/new.txt leads outside the agent's worktree through a link";

        @Override
        public Verdict decide(ToolRequested call) {
            return Verdict.ALLOW;
        }

        @Override
        public String violationNow(ToolRequested call) {
            return violation;
        }

        @Override
        public void completed(ToolCompleted result) {
            completed.add(result);
        }
    }

    @Test
    void theLastCheckTurnsAnAllowIntoACountedDenyWithItsReason() {
        Allowing inner = new Allowing();
        SessionTally tally = new SessionTally(inner, Path.of("w"), new Sandbox());
        ToolRequested call = new ToolRequested("tu1", "Write", new ObjectMapper().createObjectNode());

        assertThat(tally.decide(call)).isEqualTo(Verdict.DENY);

        assertThat(tally.allowed.get()).isZero();
        assertThat(tally.denied.get()).isEqualTo(1);
        assertThat(tally.explain("tu1")).contains("leads outside");
        assertThat(inner.completed).singleElement().satisfies(c -> {
            assertThat(c.error()).isTrue();
            assertThat(c.output()).contains("leads outside");
        });
    }

    @Test
    void withoutAViolationTheAllowStands() {
        Allowing inner = new Allowing();
        inner.violation = null;
        SessionTally tally = new SessionTally(inner, Path.of("w"), new Sandbox());

        assertThat(tally.decide(new ToolRequested("tu1", "Read", new ObjectMapper().createObjectNode())))
                .isEqualTo(Verdict.ALLOW);
        assertThat(tally.allowed.get()).isEqualTo(1);
        assertThat(inner.completed).isEmpty();
    }
}
