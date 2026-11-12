package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.CheckRun;
import dev.floor42.domain.Criticality;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Phrase;
import dev.floor42.domain.Refusal;
import dev.floor42.domain.ReviewVerdict;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketId;
import dev.floor42.domain.workflow.Workflow;
import dev.floor42.server.runtime.Sandbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The tests of a graph node, run by Floor42 and not by an agent (ADR-0062) once the developer delivers, before the
 * node goes to review. Each approved command runs as a proof step does (ADR-0058): in the WSL sandbox, offline, on a
 * throwaway copy of the worktree that is deleted after, never on the host. The sandbox off or not working: nothing
 * runs, and the run is recorded as not run. A sandbox that works but a run that cannot be set up (a copy over the size
 * cap, an IO error) is recorded as not run too, but the node does not go to review: the director is asked. Every run is a {@code ChecksRan} whose trigger names the node. A failure
 * is a rejection of the node's single rework: the first sends the node back to its developer, the second asks the
 * director.
 */
@ApplicationScoped
public class NodeTests {

    private static final Logger LOG = Logger.getLogger(NodeTests.class);

    /** The rejections after which the director is asked (the graph mode's single rework). */
    private static final int CAP = 2;

    @Inject
    OfficeService office;

    @Inject
    Workshop workshop;

    @Inject
    AgentWorktrees worktrees;

    @Inject
    ProofSnapshot snapshot;

    @Inject
    ProofShell shell;

    @Inject
    Sandbox sandbox;

    @Inject
    DeliverablesHome home;

    /**
     * Runs the node's tests; true when the node may go to review: it has no test, the tests did not run (said so in the
     * journal), or they passed.
     */
    boolean mayBeReviewed(MissionId m, TicketId node, AgentId developer) {
        Optional<List<String>> tests = office.query(c -> c.tickets()
                .find(node)
                .filter(t -> c.workflows().of(m).runnable().map(Workflow::graph).orElse(false))
                .flatMap(t -> c.workflows().nodeOf(node))
                .map(n -> n.tests())
                .filter(l -> !l.isEmpty()));
        if (tests.isEmpty()) return true;
        Origin origin = office.query(c -> c.missions().find(m).orElseThrow().origin());
        if (!sandbox.enabled() || !shell.works()) {
            record(m, origin, developer, node, tests.get().getFirst(), null, false, 0, NOT_RUN);
            return true;
        }
        Outcome outcome = run(m, origin, developer, node, tests.get());
        if (outcome == Outcome.FAILED) askIfRejectedTooOften(m, node);
        return outcome == Outcome.PASSED;
    }

    static final String NOT_RUN = "The node test did not run: the sandbox is off or does not work on this machine, and"
            + " a test written by an agent never runs outside it.";

    private enum Outcome {
        PASSED,
        FAILED,
        NOT_SET_UP
    }

    private Outcome run(MissionId m, Origin origin, AgentId developer, TicketId node, List<String> tests) {
        Path copy = null;
        try {
            Path worktree = worktrees.path(m, developer);
            copy = home.changing(m, () -> snapshot.copy(worktree, "node-" + node.value()))
                    .folder();
            for (String command : tests) {
                BoundedProcess.Ran r = shell.run(copy, command, worktree);
                boolean failed = r.timedOut() || (r.exitCode() != null && r.exitCode() != 0);
                Optional<String> missing = failed ? missingDependency(r.output()) : Optional.empty();
                if (missing.isPresent())
                    return notRunForDependencies(m, origin, developer, node, command, r, missing.get());
                record(m, origin, developer, node, command, r.exitCode(), r.timedOut(), r.durationMs(), r.output());
                if (failed) return Outcome.FAILED;
            }
            return Outcome.PASSED;
        } catch (RuntimeException e) {
            // Handled: a test that cannot be set up never lets the node go to review untested; the director is asked
            LOG.warnf("Mission %s: the tests of %s could not run: %s", m, node, e.getMessage());
            record(m, origin, developer, node, tests.getFirst(), null, false, 0, "The node test did not run: " + e);
            askNotRun(m, node, String.valueOf(e.getMessage()));
            return Outcome.NOT_SET_UP;
        } finally {
            ProofFolders.delete(copy);
        }
    }

    /** Not the developer's failure: the offline copy lacked a dependency, so nothing counts as rejected. */
    private Outcome notRunForDependencies(
            MissionId m,
            Origin origin,
            AgentId developer,
            TicketId node,
            String command,
            BoundedProcess.Ran r,
            String line) {
        String reason = "dependencies missing (" + line + ")";
        record(
                m,
                origin,
                developer,
                node,
                command,
                null,
                false,
                r.durationMs(),
                "The tests could not run: " + reason + ". " + r.output());
        askNotRun(m, node, reason);
        return Outcome.NOT_SET_UP;
    }

    private static final java.util.regex.Pattern MISSING = java.util.regex.Pattern.compile(
            "EAI_AGAIN|ENOTFOUND|getaddrinfo|Could not resolve|npm ERR! code E404|Could not transfer artifact");

    /** The first line of the output showing that a dependency could not be fetched (offline copy), if any. */
    static Optional<String> missingDependency(String output) {
        return output.lines()
                .filter(l -> MISSING.matcher(l).find())
                .findFirst()
                .map(l -> BriefSections.clip(l.strip(), 200));
    }

    private void record(
            MissionId m,
            Origin origin,
            AgentId developer,
            TicketId node,
            String command,
            Integer exit,
            boolean timedOut,
            long durationMs,
            String output) {
        String head = workshop.head(workshop.workspace(m, developer));
        unlessClosed(
                m,
                node,
                () -> office.execute(c -> c.verification()
                        .recordChecks(
                                office.context(Actor.system(), origin),
                                m,
                                developer,
                                command,
                                exit,
                                timedOut,
                                durationMs,
                                output,
                                head,
                                CheckRun.nodeTestTrigger(node))));
    }

    /** The mission was cancelled while the test ran: the result is dropped, nothing is recorded after the end. */
    private void unlessClosed(MissionId m, TicketId node, Runnable record) {
        try {
            record.run();
        } catch (DomainException e) {
            if (!Refusal.MISSION_CLOSED.code().equals(e.code())) throw e;
            LOG.infof("Mission %s is closed: the test result of %s is not recorded", m, node);
        }
    }

    private void askNotRun(MissionId m, TicketId node, String reason) {
        office.execute(c -> c.desk()
                .ask(
                        office.context(Actor.system(), Origin.REAL),
                        m,
                        "The tests of " + node + " did not run",
                        "Floor42 could not run the node's tests (" + reason + "), so the node was not sent to review."
                                + " Send it back to its developer, or accept it as it is.",
                        List.of(),
                        null,
                        Criticality.HIGH,
                        false,
                        node,
                        Phrase.of("nodeTestNotRun", "ticket", node.value(), "reason", reason)));
    }

    /** The node's rejections, reviews and failed tests together: the second asks the director (ADR-0062). */
    private void askIfRejectedTooOften(MissionId m, TicketId node) {
        long rejections = office.query(c -> rejections(c.tickets().find(node).orElseThrow())
                + c.verification().nodeTestFailures(node));
        if (rejections >= CAP) ReviewRunner.askRejectedTooOften(office, m, node, rejections);
    }

    private static long rejections(Ticket t) {
        return t.reviews().stream()
                .filter(r -> r.verdict() == ReviewVerdict.REJECTED)
                .count();
    }

    /** What the developer is told when the last test of the node failed: its command and the end of its output. */
    static String failureBrief(dev.floor42.domain.Company c, Ticket t) {
        List<CheckRun> runs = c.verification().checkRuns(t.mission()).stream()
                .filter(r -> r.nodeTest().filter(t.id()::equals).isPresent())
                .toList();
        if (runs.isEmpty() || !runs.getLast().failed()) return "";
        CheckRun last = runs.getLast();
        return "\nFloor42 ran your node's test after your last delivery, offline in the sandbox, and it failed (this"
                + " counts as a rejection; the next one asks the director):\n- command: " + last.command()
                + "\n- exit code: " + (last.exitCode() == null ? "none (timed out)" : last.exitCode())
                + "\n- output (data):\n" + BriefSections.clip(last.output(), 1_500) + "\n";
    }
}
