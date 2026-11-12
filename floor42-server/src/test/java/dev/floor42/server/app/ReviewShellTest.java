package dev.floor42.server.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.TicketId;
import dev.floor42.server.runtime.AgentRuntime.SessionSpec;
import dev.floor42.server.runtime.Sandbox;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When a reviewer is offered a shell (ADR-0046, amended 2026-11-05): only with the sandbox on and working. */
class ReviewShellTest {

    private static Sandbox sandbox(boolean enabled, boolean available) {
        return new Sandbox() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public boolean available() {
                return available;
            }
        };
    }

    @Test
    void theShellIsOfferedOnlyWithTheSandboxOnAndWorking() {
        assertThat(ReviewRunner.mayShell(false, sandbox(true, true))).isTrue();
        assertThat(ReviewRunner.mayShell(false, sandbox(true, false)))
                .as("not working")
                .isFalse();
        assertThat(ReviewRunner.mayShell(false, sandbox(false, true))).as("off").isFalse();
        assertThat(ReviewRunner.mayShell(true, sandbox(true, true)))
                .as("a researcher's folder")
                .isFalse();
    }

    @Test
    void bashIsTheOnlyToolAddedAndSkillAndAgentGo() {
        // A skill's !command lines and a sub-agent's Bash are not measured to go through the sandbox wrapping.
        assertThat(ReviewRunner.withBash(ReviewRunner.REVIEW_TOOLS))
                .containsAll(ReviewRunner.REVIEW_TOOLS.stream()
                        .filter(t -> !t.equals("Skill") && !t.equals("Agent"))
                        .toList())
                .contains("Bash")
                .doesNotContain("Skill", "Agent")
                .doesNotContain("Write", "Edit", "NotebookEdit", "PowerShell", "WebFetch", "WebSearch");
        assertThat(ReviewRunner.REVIEW_TOOLS).doesNotContain("Bash");
    }

    @Test
    void theReadOnlyShellFlagSurvivesTheWithersAndIsOffByDefault() {
        SessionSpec plain = new SessionSpec(
                new MissionId("M-1"),
                new TicketId("T-1"),
                new AgentId("a"),
                Origin.REAL,
                "C:/w",
                "b",
                "m",
                "low",
                List.of());

        assertThat(plain.readOnlyShell()).isFalse();
        SessionSpec reviewing = plain.withReadOnlyShell();
        assertThat(reviewing.readOnlyShell()).isTrue();
        assertThat(reviewing.resuming("s1", "again").readOnlyShell()).isTrue();
        assertThat(reviewing.capped(1.0).readOnlyShell()).isTrue();
    }
}
