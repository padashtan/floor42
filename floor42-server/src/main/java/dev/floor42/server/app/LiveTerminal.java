package dev.floor42.server.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.server.app.DirectorTerminals.Sink;
import dev.floor42.server.app.DirectorTerminals.TerminalView;
import dev.floor42.server.runtime.pty.Pty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * One running terminal of the director's ({@link DirectorTerminals}): its PTY, the browsers attached to it, the screen
 * kept in memory for a browser that reattaches (never journaled), and the cost its status line last reported.
 */
final class LiveTerminal {

    private static final Logger LOG = Logger.getLogger(LiveTerminal.class);

    final String id;
    final String project;
    final Instant openedAt;
    final Pty pty;
    final Path status;
    final List<Sink> sinks = new CopyOnWriteArrayList<>();
    /** Whether its end was journaled already (closed by the director, exited, or interrupted by a stop). */
    final AtomicBoolean recorded = new AtomicBoolean();

    private final byte[] ring;
    private int ringStart;
    private int ringSize;
    private final ObjectMapper json;
    private final Clock clock;
    /** Told the exit code (null when unknown) once the program ended. */
    private final Consumer<Integer> exited;

    private volatile boolean running = true;
    private volatile Integer exitCode;
    private volatile TerminalStatusLine.Reported reported;
    private volatile Instant reportedAt;
    private long statusModified = -1;

    LiveTerminal(
            String id,
            String project,
            Instant openedAt,
            Pty pty,
            Path status,
            int replayBytes,
            ObjectMapper json,
            Clock clock,
            Consumer<Integer> exited) {
        this.id = id;
        this.project = project;
        this.openedAt = openedAt;
        this.pty = pty;
        this.status = status;
        this.ring = new byte[replayBytes];
        this.json = json;
        this.clock = clock;
        this.exited = exited;
    }

    TerminalView view() {
        TerminalStatusLine.Reported r = reported;
        return new TerminalView(
                id,
                project,
                openedAt.toString(),
                running,
                r == null ? null : r.costUsd(),
                r == null || reportedAt == null ? null : reportedAt.toString(),
                r == null ? null : r.model(),
                exitCode);
    }

    /** Copies the program's output to the screen kept in memory and to every attached browser, until it ends. */
    void pump() {
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = pty.read(buffer)) > 0) {
                byte[] chunk = Arrays.copyOf(buffer, n);
                synchronized (this) {
                    keep(chunk);
                    for (Sink s : sinks) send(s, chunk);
                }
            }
        } catch (IOException e) {
            // Handled: logged; the output ends with the terminal's process
            LOG.debugf("Terminal %s output ended: %s", id, e.getMessage());
        }
    }

    private void send(Sink s, byte[] chunk) {
        try {
            s.output(chunk);
        } catch (RuntimeException e) {
            // Handled: a sink that fails is a browser gone: dropped
            sinks.remove(s);
        }
    }

    /** Waits for the program's end, then tells the owner and every browser. */
    void watch() {
        Integer code = null;
        try {
            code = pty.waitFor();
        } catch (InterruptedException e) {
            // Handled: the interrupt is kept for the caller; the exit code stays unknown
            Thread.currentThread().interrupt();
        }
        exitCode = code;
        running = false;
        pty.close();
        exited.accept(code);
        TerminalView v = view();
        for (Sink s : sinks) {
            try {
                s.ended(v);
            } catch (RuntimeException e) {
                // Handled: the browser is gone
            }
        }
        LOG.infof("Terminal %s on %s ended (exit code %s)", id, project, code);
    }

    /** Reads what the status line last wrote, and tells the browsers when it changed. */
    void readStatus() {
        long modified;
        try {
            if (!Files.isRegularFile(status)) return;
            modified = Files.getLastModifiedTime(status).toMillis() ^ Files.size(status);
        } catch (IOException e) {
            // Handled: read again a second later
            return;
        }
        if (modified == statusModified) return;
        Optional<TerminalStatusLine.Reported> r = TerminalStatusLine.read(json, status);
        if (r.isEmpty()) return;
        statusModified = modified;
        if (r.get().equals(reported)) return;
        reported = r.get();
        reportedAt = clock.instant();
        TerminalView v = view();
        for (Sink s : sinks) {
            try {
                s.status(v);
            } catch (RuntimeException e) {
                // Handled: a sink that fails is a browser gone: dropped
                sinks.remove(s);
            }
        }
    }

    private void keep(byte[] chunk) {
        for (byte b : chunk) {
            int at = (ringStart + ringSize) % ring.length;
            ring[at] = b;
            if (ringSize < ring.length) ringSize++;
            else ringStart = (ringStart + 1) % ring.length;
        }
    }

    /** What the screen shows, oldest byte first. */
    byte[] replay() {
        byte[] out = new byte[ringSize];
        for (int i = 0; i < ringSize; i++) out[i] = ring[(ringStart + i) % ring.length];
        return out;
    }
}
