package dev.floor42.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Stopping a session's process tree (ADR-0049): its end is waited for, so nobody is let go while it still runs. */
class SessionProcessesTest {

    @Test
    void aStoppedTreeHasEndedWhenStopReturns() throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        // A shell with a long child: the session's process and the command it started.
        Process shell = new ProcessBuilder(
                        windows
                                ? List.of("cmd", "/c", "ping -n 60 127.0.0.1 > NUL")
                                : List.of("sh", "-c", "sleep 60; true"))
                .start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (shell.descendants().findAny().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        List<ProcessHandle> children = shell.descendants().toList();
        assertThat(children).as("the shell started its command").isNotEmpty();

        SessionProcesses.stopTree(shell);

        assertThat(shell.isAlive()).as("the session's process has ended").isFalse();
        assertThat(children).noneMatch(ProcessHandle::isAlive);
    }

    @Test
    void theRecordedProcessIsKnownWhileItRunsAndNeverByANumberAlone(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Process p = new ProcessBuilder(
                        System.getProperty("os.name").startsWith("Windows")
                                ? List.of("cmd", "/c", "ping -n 60 127.0.0.1 > NUL")
                                : List.of("sh", "-c", "sleep 60"))
                .start();
        try {
            SessionProcesses.remember(p, dir);
            assertThat(SessionProcesses.recorded(dir)).map(ProcessHandle::pid).hasValue(p.pid());
            java.nio.file.Files.writeString(
                    dir.resolve(SessionProcesses.PROCESS_FILE), p.pid() + " 2001-01-01T00:00:00Z");
            assertThat(SessionProcesses.recorded(dir))
                    .as("same number, another start: another process")
                    .isEmpty();
        } finally {
            SessionProcesses.stopTree(p);
        }
        SessionProcesses.forget(dir);
        assertThat(SessionProcesses.recorded(dir)).isEmpty();
    }

    @Test
    void aCorruptRecordIsDeletedAndTheOtherSessionsAreStillLookedAt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path sessions) throws Exception {
        java.nio.file.Path bad = java.nio.file.Files.createDirectory(sessions.resolve("a-bad"));
        java.nio.file.Files.writeString(bad.resolve(SessionProcesses.PROCESS_FILE), "not-a-pid x");
        Process p = new ProcessBuilder(
                        System.getProperty("os.name").startsWith("Windows")
                                ? List.of("cmd", "/c", "ping -n 60 127.0.0.1 > NUL")
                                : List.of("sh", "-c", "sleep 60"))
                .start();
        try {
            java.nio.file.Path good = java.nio.file.Files.createDirectory(sessions.resolve("b-good"));
            SessionProcesses.remember(p, good);

            assertThat(SessionProcesses.stopLeftovers(sessions)).isEqualTo(1);

            assertThat(bad.resolve(SessionProcesses.PROCESS_FILE)).doesNotExist();
            assertThat(p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void anInterruptedCallerStillWaitsForTheEndAndKeepsItsInterrupt() {
        Process p = null;
        try {
            p = new ProcessBuilder(
                            System.getProperty("os.name").startsWith("Windows")
                                    ? List.of("cmd", "/c", "ping -n 60 127.0.0.1 > NUL")
                                    : List.of("sh", "-c", "sleep 60"))
                    .start();
            Thread.currentThread().interrupt();

            SessionProcesses.stopTree(p);

            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt is restored")
                    .isTrue();
            assertThat(p.isAlive()).as("the end was waited for").isFalse();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            Thread.interrupted();
            if (p != null) p.destroyForcibly();
        }
    }
}
