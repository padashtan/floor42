package dev.floor42.domain;

import java.time.Instant;

/**
 * One run of the mission's checks (build, tests) by Floor42 in an agent's workspace. Exit code, duration and output are
 * measured by Floor42, not reported by an agent.
 *
 * @param exitCode null when the run timed out or could not start
 * @param head the commit the workspace was on; pending changes were included
 * @param trigger who asked: the director, or Floor42 after a delivery
 */
public record CheckRun(
        MissionId mission,
        AgentId workspace,
        String command,
        Integer exitCode,
        boolean timedOut,
        long durationMs,
        String output,
        String head,
        String trigger,
        Instant at) {

    /** The trigger of a graph node's test (ADR-0062), followed by the ticket it belongs to: "node test T-3". */
    public static final String NODE_TEST = "node test";

    public boolean passed() {
        return exitCode != null && exitCode == 0 && !timedOut;
    }

    /** The run failed: it timed out or exited with an error. A run that never started (null exit code) did not fail. */
    public boolean failed() {
        return timedOut || (exitCode != null && exitCode != 0);
    }

    /** The node whose test this is, when the trigger says so. */
    public java.util.Optional<TicketId> nodeTest() {
        return trigger.startsWith(NODE_TEST + " ")
                ? java.util.Optional.of(new TicketId(trigger.substring(NODE_TEST.length() + 1)))
                : java.util.Optional.empty();
    }

    /** The trigger of a test of this node. */
    public static String nodeTestTrigger(TicketId node) {
        return NODE_TEST + " " + node.value();
    }
}
