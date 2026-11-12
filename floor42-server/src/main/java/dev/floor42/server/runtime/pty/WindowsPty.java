package dev.floor42.server.runtime.pty;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConPTY (Windows 10 1809 and later), called through the JDK's foreign function API: two pipes, a pseudo console
 * between them, and the program created attached to it. The calls are the ones Microsoft documents in "Creating a
 * Pseudoconsole session".
 */
final class WindowsPty implements Pty {

    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
    private static final int STARTUPINFOEX_SIZE = 112;
    private static final int INFINITE = -1;
    private static final int STILL_ACTIVE = 259;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Kernel32 NATIVE = Kernel32.load();

    private final MemorySegment console;
    private final MemorySegment input;
    private final MemorySegment output;
    private final MemorySegment processHandle;
    private final ProcessHandle process;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean consoleClosed = new AtomicBoolean();
    private final Object writing = new Object();

    private WindowsPty(
            MemorySegment console, MemorySegment input, MemorySegment output, MemorySegment processHandle, long pid) {
        this.console = console;
        this.input = input;
        this.output = output;
        this.processHandle = processHandle;
        this.process = ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("Process " + pid + " gone"));
    }

    static Optional<String> unavailable() {
        return NATIVE == null
                ? Optional.of("ConPTY is not available on this Windows (Windows 10 1809 or later is needed)")
                : Optional.empty();
    }

    static Pty start(List<String> command, Path directory, Map<String, String> environment, int columns, int rows)
            throws IOException {
        Kernel32 n = NATIVE;
        String commandLine = commandLine(Executables.resolve(command, environment));
        Arena arena = Arena.ofConfined();
        Handles h = new Handles();
        try {
            pipes(n, arena, h);
            pseudoConsole(n, arena, h, columns, rows);
            attributes(n, arena, h);
            return spawn(n, arena, h, command.getFirst(), commandLine, directory, environment);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("ConPTY call failed: " + e, e);
        } finally {
            h.release(n);
            arena.close();
        }
    }

    /** The handles a start holds until the terminal owns them: whatever is left is closed when the start ends. */
    private static final class Handles {
        MemorySegment ptyIn = MemorySegment.NULL;
        MemorySegment ptyOut = MemorySegment.NULL;
        MemorySegment ours = MemorySegment.NULL;
        MemorySegment theirs = MemorySegment.NULL;
        MemorySegment console = MemorySegment.NULL;
        MemorySegment attributes = MemorySegment.NULL;

        void release(Kernel32 n) {
            try {
                if (!attributes.equals(MemorySegment.NULL)) n.deleteAttributes().invokeExact(attributes);
            } catch (Throwable ignored) {
                // Handled: nothing more to free
            }
            closeHandle(ptyIn);
            closeHandle(ptyOut);
            closeHandle(ours);
            closeHandle(theirs);
            if (!console.equals(MemorySegment.NULL)) closeConsole(console);
        }
    }

    /** Pipe 1: what we write, the console reads. Pipe 2: what the console writes, we read. */
    private static void pipes(Kernel32 n, Arena arena, Handles h) throws Throwable {
        MemorySegment a = arena.allocate(ADDRESS);
        MemorySegment b = arena.allocate(ADDRESS);
        check((int) n.createPipe().invokeExact(a, b, MemorySegment.NULL, 0), "CreatePipe");
        h.ptyIn = a.get(ADDRESS, 0);
        h.ours = b.get(ADDRESS, 0);
        check((int) n.createPipe().invokeExact(a, b, MemorySegment.NULL, 0), "CreatePipe");
        h.theirs = a.get(ADDRESS, 0);
        h.ptyOut = b.get(ADDRESS, 0);
    }

    private static void pseudoConsole(Kernel32 n, Arena arena, Handles h, int columns, int rows) throws Throwable {
        MemorySegment hpc = arena.allocate(ADDRESS);
        int hr = (int) n.createPseudoConsole().invokeExact(coord(columns, rows), h.ptyIn, h.ptyOut, 0, hpc);
        if (hr != 0) throw new IOException("CreatePseudoConsole failed: HRESULT 0x" + Integer.toHexString(hr));
        h.console = hpc.get(ADDRESS, 0);
        // The console holds its own copies: ours would keep the pipes open after the program ends.
        closeHandle(h.ptyIn);
        h.ptyIn = MemorySegment.NULL;
        closeHandle(h.ptyOut);
        h.ptyOut = MemorySegment.NULL;
    }

    /** The attribute list that gives the new process the pseudo console. */
    private static void attributes(Kernel32 n, Arena arena, Handles h) throws Throwable {
        MemorySegment size = arena.allocate(JAVA_LONG);
        int ignored = (int) n.initializeAttributes().invokeExact(MemorySegment.NULL, 1, 0, size);
        h.attributes = arena.allocate(size.get(JAVA_LONG, 0), 8);
        check(
                (int) n.initializeAttributes().invokeExact(h.attributes, 1, 0, size),
                "InitializeProcThreadAttributeList");
        check(
                (int) n.updateAttribute()
                        .invokeExact(
                                h.attributes,
                                0,
                                PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
                                h.console,
                                ADDRESS.byteSize(),
                                MemorySegment.NULL,
                                MemorySegment.NULL),
                "UpdateProcThreadAttribute");
    }

    private static WindowsPty spawn(
            Kernel32 n,
            Arena arena,
            Handles h,
            String program,
            String commandLine,
            Path directory,
            Map<String, String> environment)
            throws Throwable {
        MemorySegment startup = arena.allocate(STARTUPINFOEX_SIZE, 8);
        startup.set(JAVA_INT, 0, STARTUPINFOEX_SIZE);
        // Null standard handles: a daemon whose own output is redirected would otherwise pass its handles on, and
        // the program would write there instead of to the console.
        startup.set(JAVA_INT, 60, STARTF_USESTDHANDLES);
        startup.set(ADDRESS, 104, h.attributes);
        MemorySegment info = arena.allocate(24, 8);
        MemorySegment state = arena.allocate(n.callState());
        int created = (int) n.createProcess()
                .invokeExact(
                        state,
                        MemorySegment.NULL,
                        arena.allocateFrom(commandLine, StandardCharsets.UTF_16LE),
                        MemorySegment.NULL,
                        MemorySegment.NULL,
                        0,
                        EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
                        arena.allocateFrom(environmentBlock(environment), StandardCharsets.UTF_16LE),
                        arena.allocateFrom(directory.toAbsolutePath().toString(), StandardCharsets.UTF_16LE),
                        startup,
                        info);
        if (created == 0) {
            int error = (int) n.lastError().get(state, 0L);
            throw new IOException("Cannot start " + program + " in a terminal: Windows error " + error);
        }
        closeHandle(info.get(ADDRESS, 8));
        long pid = Integer.toUnsignedLong(info.get(JAVA_INT, 16));
        WindowsPty pty = new WindowsPty(h.console, h.ours, h.theirs, info.get(ADDRESS, 0), pid);
        h.console = MemorySegment.NULL;
        h.ours = MemorySegment.NULL;
        h.theirs = MemorySegment.NULL;
        return pty;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(buffer.length);
            MemorySegment count = arena.allocate(JAVA_INT);
            int ok = (int) NATIVE.readFile().invokeExact(output, segment, buffer.length, count, MemorySegment.NULL);
            int read = count.get(JAVA_INT, 0);
            if (ok == 0 || read == 0) {
                // The console closed its end: the program ended or the terminal was closed.
                return -1;
            }
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, buffer, 0, read);
            return read;
        } catch (Throwable e) {
            throw new IOException("ReadFile failed: " + e, e);
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (closed.get()) throw new IOException("The terminal is closed");
        synchronized (writing) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                MemorySegment count = arena.allocate(JAVA_INT);
                long done = 0;
                while (done < bytes.length) {
                    int ok = (int) NATIVE.writeFile()
                            .invokeExact(
                                    input,
                                    segment.asSlice(done),
                                    (int) (bytes.length - done),
                                    count,
                                    MemorySegment.NULL);
                    if (ok == 0) throw new IOException("The terminal no longer takes input");
                    done += count.get(JAVA_INT, 0);
                }
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("WriteFile failed: " + e, e);
            }
        }
    }

    @Override
    public void resize(int columns, int rows) throws IOException {
        if (closed.get()) return;
        try {
            int hr = (int) NATIVE.resizePseudoConsole().invokeExact(console, coord(columns, rows));
            if (hr != 0) throw new IOException("ResizePseudoConsole failed: HRESULT 0x" + Integer.toHexString(hr));
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("ResizePseudoConsole failed: " + e, e);
        }
    }

    @Override
    public ProcessHandle process() {
        return process;
    }

    @Override
    public Integer waitFor() throws InterruptedException {
        process.onExit().join();
        if (Thread.interrupted()) throw new InterruptedException();
        try (Arena arena = Arena.ofConfined()) {
            int ignored = (int) NATIVE.waitForSingleObject().invokeExact(processHandle, INFINITE);
            MemorySegment code = arena.allocate(JAVA_INT);
            int ok = (int) NATIVE.getExitCodeProcess().invokeExact(processHandle, code);
            int value = code.get(JAVA_INT, 0);
            return ok == 0 || value == STILL_ACTIVE ? null : value;
        } catch (Throwable e) {
            // Handled: an exit code that cannot be read is unknown
            return null;
        } finally {
            // The program is gone: closing the console ends the reader's wait on the output pipe.
            releaseConsole();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        try {
            int ignored = (int) NATIVE.terminateProcess().invokeExact(processHandle, 1);
        } catch (Throwable ignored) {
            // Handled: already gone
        }
        process.destroyForcibly();
        releaseConsole();
        closeHandle(input);
    }

    private void releaseConsole() {
        if (consoleClosed.compareAndSet(false, true)) closeConsole(console);
    }

    /**
     * The command line Windows hands the program, quoted as the C runtime splits it back into arguments; a batch file
     * runs through {@code cmd.exe}, which cannot be started directly.
     */
    static String commandLine(List<String> command) {
        String first = command.getFirst().toLowerCase(Locale.ROOT);
        String line = String.join(" ", command.stream().map(WindowsPty::quote).toList());
        if (first.endsWith(".cmd") || first.endsWith(".bat")) return "cmd.exe /d /s /c \"" + line + "\"";
        return line;
    }

    static String quote(String argument) {
        if (!argument.isEmpty() && argument.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) {
            return argument;
        }
        StringBuilder b = new StringBuilder("\"");
        int backslashes = 0;
        for (char c : argument.toCharArray()) {
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                b.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                b.append("\\".repeat(backslashes)).append(c);
                backslashes = 0;
            }
        }
        return b.append("\\".repeat(backslashes * 2)).append('"').toString();
    }

    /** {@code NAME=value} strings, sorted without case as Windows expects, each ended by a NUL, then one more. */
    static String environmentBlock(Map<String, String> environment) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(environment);
        StringBuilder b = new StringBuilder();
        sorted.forEach((k, v) -> b.append(k).append('=').append(v).append('\0'));
        // allocateFrom adds the final NUL.
        return b.isEmpty() ? "\0" : b.toString();
    }

    /** A COORD passed by value: X (columns) in the low word, Y (rows) in the high word. */
    private static int coord(int columns, int rows) {
        return (Pty.clamp(rows) << 16) | (Pty.clamp(columns) & 0xffff);
    }

    private static void check(int ok, String call) throws IOException {
        if (ok == 0) throw new IOException(call + " failed");
    }

    private static void closeHandle(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) return;
        try {
            int ignored = (int) NATIVE.closeHandle().invokeExact(handle);
        } catch (Throwable ignored) {
            // Handled: nothing to do
        }
    }

    private static void closeConsole(MemorySegment console) {
        try {
            NATIVE.closePseudoConsole().invokeExact(console);
        } catch (Throwable ignored) {
            // Handled: nothing to do
        }
    }
}
