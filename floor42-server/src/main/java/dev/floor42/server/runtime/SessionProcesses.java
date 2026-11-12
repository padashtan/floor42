package dev.floor42.server.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * The Claude Code processes of live sessions: each leaves the pid and start time of its process in its folder, removed
 * when it ends. A daemon that died while sessions ran leaves them behind: the next one stops those processes. A session
 * that shows no sign of life is stopped by its watchdog.
 */
final class SessionProcesses {

    static final String PROCESS_FILE = "process.txt";

    private static final Logger LOG = Logger.getLogger(SessionProcesses.class);

    private SessionProcesses() {}

    /** How long a stopped process tree is waited for: its end, not the order to end, lets its agent go (ADR-0049). */
    static final java.time.Duration STOP_WAIT = java.time.Duration.ofSeconds(10);

    /**
     * The process and everything it started (a shell command, a build), stopped and waited for, at most {@link
     * #STOP_WAIT}: a process told to stop may still run for a while, so its end is awaited before anyone is let go.
     */
    static void stopTree(Process process) {
        java.util.List<ProcessHandle> tree = process.descendants().toList();
        tree.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        long until = System.nanoTime() + STOP_WAIT.toNanos();
        // Called after an interrupt, the wait would end at once: the flag is cleared for the wait, restored after.
        boolean interrupted = Thread.interrupted();
        try {
            // The process through its own waitFor (what its isAlive reads); its children polled until gone.
            process.waitFor(STOP_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            for (ProcessHandle h : tree) {
                while (h.isAlive() && System.nanoTime() < until) Thread.sleep(20);
            }
            if (process.isAlive() || tree.stream().anyMatch(ProcessHandle::isAlive))
                LOG.warnf("Process %d or a child did not end within %s of being stopped", process.pid(), STOP_WAIT);
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static void remember(Process process, Path dir) {
        try {
            String started = process.info().startInstant().map(Object::toString).orElse("");
            Files.writeString(dir.resolve(PROCESS_FILE), process.pid() + " " + started);
        } catch (IOException e) {
            // Handled: logged; only the clean-up after a crash misses this session
            LOG.warnf("Cannot record the process of session %s: %s", dir.getFileName(), e.getMessage());
        }
    }

    static void forget(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve(PROCESS_FILE));
        } catch (IOException e) {
            // Handled: logged; the next start finds a process record of a process gone
            LOG.warnf("Cannot clear the process record of session %s: %s", dir.getFileName(), e.getMessage());
        }
    }

    /**
     * Stops the Claude Code processes a previous daemon left running. Their hooks are refused already (their token died
     * with that daemon), but they would go on spending the plan's quota. A process is stopped only if its pid still
     * names the process that was started (same start time), never one that reused the number.
     *
     * @return how many processes were stopped
     */
    static int stopLeftovers(Path sessions) {
        if (!Files.isDirectory(sessions)) return 0;
        int stopped = 0;
        try (var dirs = Files.list(sessions)) {
            for (Path dir : dirs.toList()) {
                try {
                    if (stopLeftover(dir)) stopped++;
                } catch (NumberFormatException e) {
                    // Handled: a corrupt record is deleted, the other sessions are still looked at
                    LOG.warnf("Corrupt process record in session %s: deleted", dir.getFileName());
                    Files.deleteIfExists(dir.resolve(PROCESS_FILE));
                }
            }
        } catch (IOException e) {
            // Handled: logged; sessions left running are looked for again at the next start
            LOG.warnf("Cannot look for sessions left running: %s", e.getMessage());
        }
        return stopped;
    }

    private static boolean stopLeftover(Path dir) throws IOException {
        Path record = dir.resolve(PROCESS_FILE);
        if (!Files.isRegularFile(record)) return false;
        Optional<ProcessHandle> left = recorded(dir);
        left.ifPresent(h -> {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
            LOG.infof(
                    "Stopped Claude Code process %s left running by the previous daemon (session %s)",
                    h.pid(), dir.getFileName());
        });
        Files.deleteIfExists(record);
        return left.isPresent();
    }

    /**
     * The session's own Claude Code process, as recorded at its start: present only while that very process runs (same
     * start time), never one that reused the number. A hint for leftover cleanup only, NEVER an authorisation source:
     * the session can write its own folder; process binding uses the in-memory pid and start time of the launch.
     */
    static Optional<ProcessHandle> recorded(Path dir) throws IOException {
        Path record = dir.resolve(PROCESS_FILE);
        if (!Files.isRegularFile(record)) return Optional.empty();
        String[] p = Files.readString(record).strip().split(" ", 2);
        String started = p.length > 1 ? p[1] : "";
        return ProcessHandle.of(Long.parseLong(p[0]))
                .filter(h -> !started.isEmpty()
                        && h.info()
                                .startInstant()
                                .map(Object::toString)
                                .orElse("")
                                .equals(started));
    }

    /**
     * Stops a session that shows no sign of life (no line on its stream, no tool call) for the session timeout, unless
     * a hook call is waiting for the director. Stopping it closes its stream, which ends the reading loop.
     */
    static void watch(Process process, SessionTally tally, AtomicBoolean stalled, long timeoutSeconds) {
        long limit = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long step = Math.clamp(timeoutSeconds * 250L, 200L, 5_000L);
        try {
            while (!process.waitFor(step, TimeUnit.MILLISECONDS)) {
                if (tally.waiting.get() == 0 && System.nanoTime() - tally.lastSign.get() > limit) {
                    stalled.set(true);
                    LOG.warnf("Session process %d shows no sign of life: Floor42 stops it", process.pid());
                    stopTree(process);
                    return;
                }
            }
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept; the session ended, nothing left to watch
            Thread.currentThread().interrupt();
        }
    }
}
