package dev.floor42.server.runtime.pty;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Unix 98 pseudo-terminal on Linux, through the C library: {@code posix_openpt}, then the program started by
 * {@code setsid} in a new session, whose shell opens the terminal's other end as its standard input, output and error
 * (a session leader that opens a terminal makes it its controlling terminal). Floor42's own process never opens that
 * end, so it never becomes Floor42's terminal.
 */
final class PosixPty implements Pty {

    private static final int O_RDWR = 2;
    private static final int O_NOCTTY = 0x100;
    private static final long TIOCSWINSZ = 0x5414L;
    /** Opens the terminal named by $0 on 0, 1 and 2, then becomes the program. */
    private static final String ATTACH = "exec 0<>\"$0\" 1>&0 2>&0 && exec \"$@\"";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Native NATIVE = load();

    private final int master;
    private final Process process;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile boolean reading;
    private final Object writing = new Object();

    private PosixPty(int master, Process process) {
        this.master = master;
        this.process = process;
    }

    static Optional<String> unavailable() {
        if (NATIVE == null) return Optional.of("The C library's pseudo-terminal calls are not available");
        if (!Files.isExecutable(Path.of("/usr/bin/setsid")) && !Files.isExecutable(Path.of("/bin/setsid"))) {
            return Optional.of("setsid (util-linux) is needed to open a terminal");
        }
        return Optional.empty();
    }

    static Pty start(List<String> command, Path directory, Map<String, String> environment, int columns, int rows)
            throws IOException {
        List<String> resolved = Executables.resolve(command, environment);
        Opened pt = open(NATIVE, columns, rows);
        List<String> argv = new ArrayList<>(List.of(setsid(), "-w", "/bin/sh", "-c", ATTACH, pt.slave()));
        argv.addAll(resolved);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            return new PosixPty(pt.master(), process);
        } catch (IOException e) {
            closeFd(pt.master());
            throw e;
        }
    }

    /** A pseudo-terminal opened: its master side, kept by Floor42, and the name of its slave side. */
    private record Opened(int master, String slave) {}

    private static Opened open(Native n, int columns, int rows) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int master = (int) n.openpt.invokeExact(O_RDWR | O_NOCTTY);
            if (master < 0) throw new IOException("posix_openpt failed");
            MemorySegment name = arena.allocate(256);
            if ((int) n.grantpt.invokeExact(master) != 0
                    || (int) n.unlockpt.invokeExact(master) != 0
                    || (int) n.ptsname.invokeExact(master, name, 256L) != 0) {
                int ignored = (int) n.close.invokeExact(master);
                throw new IOException("Cannot prepare the pseudo-terminal");
            }
            winsize(n, master, columns, rows);
            return new Opened(master, name.getString(0));
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Pseudo-terminal call failed: " + e, e);
        }
    }

    private static String setsid() {
        return Files.isExecutable(Path.of("/usr/bin/setsid")) ? "/usr/bin/setsid" : "/bin/setsid";
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        if (released.get()) return -1;
        reading = true;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(buffer.length);
            long read = (long) NATIVE.read.invokeExact(master, segment, (long) buffer.length);
            if (read <= 0) {
                // EIO once every process holding the other end has gone.
                release();
                return -1;
            }
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, buffer, 0, (int) read);
            return (int) read;
        } catch (Throwable e) {
            throw new IOException("read failed: " + e, e);
        } finally {
            reading = false;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        if (closed.get() || released.get()) throw new IOException("The terminal is closed");
        synchronized (writing) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                long done = 0;
                while (done < bytes.length) {
                    long n = (long) NATIVE.write.invokeExact(master, segment.asSlice(done), bytes.length - done);
                    if (n <= 0) throw new IOException("The terminal no longer takes input");
                    done += n;
                }
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("write failed: " + e, e);
            }
        }
    }

    @Override
    public void resize(int columns, int rows) throws IOException {
        if (closed.get() || released.get()) return;
        try {
            winsize(NATIVE, master, columns, rows);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("ioctl failed: " + e, e);
        }
    }

    @Override
    public ProcessHandle process() {
        return process.toHandle();
    }

    @Override
    public Integer waitFor() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        // A reader blocked on the terminal gets EIO now that its programs are gone, and releases it itself.
        if (!reading) release();
    }

    private void release() {
        if (released.compareAndSet(false, true)) closeFd(master);
    }

    private static void winsize(Native n, int fd, int columns, int rows) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(8, 2);
            size.set(JAVA_SHORT, 0, Pty.clamp(rows));
            size.set(JAVA_SHORT, 2, Pty.clamp(columns));
            if ((int) n.ioctl.invokeExact(fd, TIOCSWINSZ, size) != 0) throw new IOException("TIOCSWINSZ failed");
        }
    }

    private static void closeFd(int fd) {
        try {
            int ignored = (int) NATIVE.close.invokeExact(fd);
        } catch (Throwable ignored) {
            // Handled: nothing to do
        }
    }

    private record Native(
            MethodHandle openpt,
            MethodHandle grantpt,
            MethodHandle unlockpt,
            MethodHandle ptsname,
            MethodHandle ioctl,
            MethodHandle read,
            MethodHandle write,
            MethodHandle close) {}

    private static Native load() {
        if (Pty.windows()) return null;
        try {
            var libc = LINKER.defaultLookup();
            return new Native(
                    h(libc, "posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT)),
                    h(libc, "grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT)),
                    h(libc, "unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT)),
                    h(libc, "ptsname_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG)),
                    LINKER.downcallHandle(
                            libc.find("ioctl").orElseThrow(),
                            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
                            Linker.Option.firstVariadicArg(2)),
                    h(libc, "read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)),
                    h(libc, "write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)),
                    h(libc, "close", FunctionDescriptor.of(JAVA_INT, JAVA_INT)));
        } catch (RuntimeException e) {
            // Handled: no C library at hand: no terminal on this system, the caller says so
            return null;
        }
    }

    private static MethodHandle h(java.lang.foreign.SymbolLookup lookup, String name, FunctionDescriptor d) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), d);
    }
}
