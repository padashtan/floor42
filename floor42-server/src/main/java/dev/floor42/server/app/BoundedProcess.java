package dev.floor42.server.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A command Floor42 runs itself and measures (the director's checks, a delivery's proof): its output read while it
 * runs and capped to its end, stopped with its children at a timeout, run without Floor42's or Claude Code's
 * environment.
 */
public final class BoundedProcess {

    static final int MAX_OUTPUT = 8_000;

    /**
     * What was measured.
     *
     * @param exitCode null when the command timed out or could not start
     */
    public record Ran(Integer exitCode, boolean timedOut, long durationMs, String output) {}

    private BoundedProcess() {}

    /**
     * A process builder that hides Claude Code's settings and Floor42's own secrets (still accepted under Office's
     * names), and forwards nothing into WSL ({@link dev.floor42.server.runtime.Sandbox#scrub}).
     */
    static ProcessBuilder builder(List<String> argv, java.nio.file.Path dir) {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
        dev.floor42.server.runtime.SandboxEnv.scrub(pb.environment());
        return pb;
    }

    /** Starts the process and waits for it; a process that cannot start is measured as such, never thrown. */
    static Ran run(ProcessBuilder pb, long timeoutSeconds, String what) {
        long start = System.nanoTime();
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            // Handled: the result tells the director the command could not start
            return new Ran(null, false, 0, "The " + what + " could not start: " + e.getMessage());
        }
        return await(p, start, timeoutSeconds, what);
    }

    /** Reads the output while the process runs, stops it at the timeout, children too. */
    static Ran await(Process p, long start, long timeoutSeconds, String what) {
        closeInput(p);
        Tail tail = new Tail(p.getInputStream());
        Thread reader = Thread.ofVirtual().start(tail);
        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the process is stopped as if timed out
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        try {
            reader.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the output read so far is returned
            Thread.currentThread().interrupt();
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String output =
                tail.text() + (finished ? "" : "\n[Floor42 stopped the " + what + " after " + timeoutSeconds + " s]");
        return new Ran(finished ? p.exitValue() : null, !finished, durationMs, output);
    }

    private static void closeInput(Process p) {
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // Handled: nothing to send anyway
        }
    }

    /** Keeps the end of the output, where builds and test runners put their verdict. */
    private static final class Tail implements Runnable {
        private final InputStream in;
        private final StringBuilder text = new StringBuilder();
        private boolean cut;

        Tail(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (this) {
                        text.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        if (text.length() > 4 * MAX_OUTPUT) {
                            text.delete(0, text.length() - MAX_OUTPUT);
                            cut = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Handled: the process was stopped
            }
        }

        synchronized String text() {
            String s = text.toString().replace("\r\n", "\n");
            if (s.length() > MAX_OUTPUT) {
                s = s.substring(s.length() - MAX_OUTPUT);
                cut = true;
            }
            return cut ? "…\n" + s : s;
        }
    }
}
