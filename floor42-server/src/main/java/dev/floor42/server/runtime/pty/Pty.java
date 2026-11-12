package dev.floor42.server.runtime.pty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A program running in a pseudo-terminal: it sees a real terminal (colours, cursor keys, its window size), and Floor42
 * holds the other end. Written on the JDK's own foreign function API (ADR-0041): ConPTY on Windows, a Unix 98 pseudo
 * terminal on Linux; no library.
 *
 * <p>Nothing here interprets what the program prints or what is typed: bytes go through as they are.
 */
public interface Pty extends AutoCloseable {

    /** Reads what the program printed, blocking; -1 once the terminal is closed and every byte was read. */
    int read(byte[] buffer) throws IOException;

    /** Sends bytes to the program, as if typed on its keyboard. */
    void write(byte[] bytes) throws IOException;

    /** Tells the program its window changed size. */
    void resize(int columns, int rows) throws IOException;

    /** The program's process (the one started, not its children). */
    ProcessHandle process();

    /** Waits for the program to end, and gives its exit code (null when the system does not tell). */
    Integer waitFor() throws InterruptedException;

    /** Stops the program and every process it started, then releases the terminal. Safe to call twice. */
    @Override
    void close();

    /** Starts {@code command} in a new pseudo-terminal, in {@code directory}, with exactly {@code environment}. */
    static Pty start(List<String> command, Path directory, Map<String, String> environment, int columns, int rows)
            throws IOException {
        Optional<String> missing = unavailable();
        if (missing.isPresent()) throw new IOException(missing.get());
        if (windows()) return WindowsPty.start(command, directory, environment, columns, rows);
        return PosixPty.start(command, directory, environment, columns, rows);
    }

    /** Why no pseudo-terminal can be opened on this system, empty when one can. */
    static Optional<String> unavailable() {
        if (windows()) return WindowsPty.unavailable();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return PosixPty.unavailable();
        return Optional.of("No pseudo-terminal support on " + System.getProperty("os.name") + " yet (Windows, Linux)");
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /** Keeps a size within what both systems accept. */
    static short clamp(int value) {
        return (short) Math.max(2, Math.min(value, 1000));
    }
}
