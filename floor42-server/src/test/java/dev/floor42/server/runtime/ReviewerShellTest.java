package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.server.runtime.AgentRuntime.Governor;
import dev.floor42.server.runtime.AgentRuntime.Verdict;
import dev.floor42.server.runtime.RuntimeEvent.ToolCompleted;
import dev.floor42.server.runtime.RuntimeEvent.ToolRequested;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A reviewer's shell (ADR-0046, amended 2026-11-05): it runs only when the sandbox is on and works, and then always
 * with the work mounted read-only. Any other session keeps today's rule.
 */
class ReviewerShellTest {

    private static final Path WORK = Path.of("C:/w/M-1-chris");

    private static Sandbox sandbox(boolean on, Boolean works) {
        return sandbox(on, works, false);
    }

    private static Sandbox sandbox(boolean on, Boolean works, boolean network) {
        Sandbox s = new Sandbox();
        s.distro = "Debian";
        s.osName = "Windows 11"; // the line asserted below is the Windows one
        s.dataDir = Path.of("target", "reviewer-shell-test");
        s.policy = new SandboxPolicy() {
            @Override
            public boolean sandboxEnabled() {
                return on;
            }

            @Override
            public boolean sandboxNetwork() {
                return network;
            }
        };
        s.setAvailable(works);
        return s;
    }

    /** The mission's table said yes: what is left to refuse is the tally's own rule. */
    private static final Governor ALLOWS = new Governor() {
        @Override
        public Verdict decide(ToolRequested call) {
            return Verdict.ALLOW;
        }

        @Override
        public String violationNow(dev.floor42.server.runtime.RuntimeEvent.ToolRequested call) {
            return null;
        }

        @Override
        public void completed(ToolCompleted result) {}
    };

    private static ToolRequested bash(String command) {
        ObjectNode in = new ObjectMapper().createObjectNode().put("command", command);
        return new ToolRequested("tu1", "Bash", in);
    }

    private static SessionTally reviewer(Sandbox s) {
        return new SessionTally(ALLOWS, WORK, s, true);
    }

    @Test
    void withTheSandboxOnAndWorkingTheReviewersShellRunsInItReadOnly() {
        SessionTally tally = reviewer(sandbox(true, true));

        assertThat(tally.decide(bash("mvn test"))).isEqualTo(Verdict.ALLOW);
        String line = tally.updatedInput(bash("mvn test")).path("command").asText();
        assertThat(line).startsWith("WSLENV= MSYS_NO_PATHCONV=1 wsl.exe -d Debian --exec sh ");
        assertThat(line).as("the last argument asks for the read-only mount").endsWith(" 'ro'");
    }

    @Test
    void otherSessionsKeepTheWritableMount() {
        SessionTally tally = new SessionTally(ALLOWS, WORK, sandbox(true, true));

        String line = tally.updatedInput(bash("mvn test")).path("command").asText();

        assertThat(line).endsWith(" 'rw'");
    }

    @Test
    void withTheSandboxOffTheReviewersShellIsRefusedAndNeverRewrittenToRunOutside() {
        SessionTally tally = reviewer(sandbox(false, true));

        assertThat(tally.decide(bash("mvn test"))).isEqualTo(Verdict.DENY);
        assertThat(tally.explain("tu1")).contains("reviewer").contains("sandbox");
        assertThatThrownBy(() -> tally.updatedInput(bash("mvn test"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withTheSandboxOnButNotWorkingTheReviewersShellIsRefused() {
        SessionTally tally = reviewer(sandbox(true, false));

        assertThat(tally.decide(bash("mvn test"))).isEqualTo(Verdict.DENY);
    }

    @Test
    void aReadingToolIsNotTouchedByTheRule() {
        SessionTally tally = reviewer(sandbox(false, false));
        ObjectNode in = new ObjectMapper().createObjectNode().put("file_path", "a.txt");

        assertThat(tally.decide(new ToolRequested("tu2", "Read", in))).isEqualTo(Verdict.ALLOW);
        assertThat(tally.updatedInput(new ToolRequested("tu2", "Read", in))).isNull();
    }

    @Test
    void aReviewersShellIsOfflineWhateverTheDirectorOpenedForTheAgents() {
        Sandbox open = sandbox(true, true, true);

        String reviewing =
                reviewer(open).updatedInput(bash("mvn test")).path("command").asText();
        String producing = new SessionTally(ALLOWS, WORK, open)
                .updatedInput(bash("mvn test"))
                .path("command")
                .asText();

        assertThat(reviewing).contains(" 'off' ").doesNotContain(" 'on' ");
        assertThat(producing).as("the others keep the director's setting").contains(" 'on' ");
    }

    @Test
    void aReviewersCallThatCannotBeWrappedIsRefusedNotRunAsItCame() {
        SessionTally tally = reviewer(sandbox(true, true));
        ObjectNode blank = new ObjectMapper().createObjectNode().put("command", " ");
        var text = new ObjectMapper().getNodeFactory().textNode("ls");

        assertThatThrownBy(() -> tally.updatedInput(new ToolRequested("tu3", "Bash", blank)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tally.updatedInput(new ToolRequested("tu4", "Bash", text)))
                .isInstanceOf(IllegalStateException.class);
    }
}
