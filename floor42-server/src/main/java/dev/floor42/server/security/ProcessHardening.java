package dev.floor42.server.security;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * On Linux, the daemon makes itself non-dumpable at start ({@code prctl(PR_SET_DUMPABLE, 0)}, ADR-0044). Agents run
 * as the same OS user; a dumpable process lets any process of that user open {@code /proc/<pid>/fd/*} (in Docker the
 * daemon's standard output, which carries the sign-in link), {@code environ} and {@code mem}, and attach a debugger.
 * A non-dumpable process's {@code /proc} entries belong to root: the same user is refused. Agents' own processes are
 * started afresh by {@code exec} and are dumpable again, as before.
 *
 * <p>What it does not do: it does not protect the files the daemon writes (the journal), which the same user can still
 * read and write; nor any process on Windows or macOS. Off with {@code floor42.process.non-dumpable=false} (needed for
 * {@code jcmd} or a debugger).
 */
@ApplicationScoped
public class ProcessHardening {

    private static final Logger LOG = Logger.getLogger(ProcessHardening.class);
    static final int PR_GET_DUMPABLE = 3;
    static final int PR_SET_DUMPABLE = 4;

    @ConfigProperty(name = "floor42.process.non-dumpable", defaultValue = "true")
    boolean enabled;

    @Inject
    Config config;

    /**
     * What /api/status shows: measured from the kernel when asked, never what was intended.
     *
     * @param requested {@code floor42.process.non-dumpable}
     * @param nonDumpable the kernel's answer now ({@code PR_GET_DUMPABLE} is 0); null where it cannot be asked
     *     (Windows, macOS)
     */
    public record State(boolean requested, Boolean nonDumpable, Boolean attachDisabled) {}

    /**
     * {@code -XX:+DisableAttachMechanism}, as the running JVM reports it. Non-dumpable does not cover the JVM's own
     * attach mechanism: a same-user process creating {@code /tmp/.attach_pid<pid>} and sending SIGQUIT made the
     * daemon open {@code /tmp/.java_pid<pid>}, through which it could load an agent into the daemon (checked in a 1.8.0
     * container before this flag, 2026-11-01). Null when the JVM does not say.
     */
    public static Boolean attachDisabled() {
        try {
            com.sun.management.HotSpotDiagnosticMXBean hotspot =
                    java.lang.management.ManagementFactory.getPlatformMXBean(
                            com.sun.management.HotSpotDiagnosticMXBean.class);
            return Boolean.parseBoolean(
                    hotspot.getVMOption("DisableAttachMechanism").getValue());
        } catch (RuntimeException | LinkageError e) {
            // Handled: the JVM does not say: null, reported as unknown
            return null;
        }
    }

    void start(@Observes StartupEvent ignored) {
        if (!Boolean.TRUE.equals(attachDisabled())) {
            LOG.warn("The JVM's attach mechanism is on: a process of the same user can load code into the daemon."
                    + " Start it with -XX:+DisableAttachMechanism, as the launchers and the Docker image do");
        }
        if (!enabled) {
            // Where it was turned off: the director's environment, or a file someone planted (ConfigGuard refuses those
            // read from the working directory).
            LOG.warnf(
                    "floor42.process.non-dumpable=false (set by %s): processes of the same user can read the daemon's"
                            + " /proc entries",
                    config == null
                            ? "?"
                            : config.getConfigValue("floor42.process.non-dumpable")
                                    .getSourceName());
            return;
        }
        if (!linux()) return;
        Optional<Integer> after = makeNonDumpable();
        if (after.isPresent() && after.get() == 0) {
            LOG.info("Daemon made non-dumpable: same-user processes cannot read its /proc entries (ADR-0044)");
        } else {
            LOG.error("Could not make the daemon non-dumpable: same-user processes can read its /proc entries");
        }
    }

    /** Asked of the kernel now: a later change (another library calling prctl) shows too. */
    public State state() {
        return new State(enabled, dumpable().map(d -> d == 0).orElse(null), attachDisabled());
    }

    static boolean linux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    /** Sets the flag and reads it back: 0 when the daemon is now non-dumpable; empty when prctl is out of reach. */
    static Optional<Integer> makeNonDumpable() {
        try {
            MethodHandle prctl = prctl();
            int set = (int) prctl.invokeExact(PR_SET_DUMPABLE, 0L);
            if (set != 0) return Optional.empty();
            return dumpable(prctl);
        } catch (Throwable e) {
            // Handled: logged; the daemon stays dumpable and says so at start
            LOG.debugf("prctl unavailable: %s", e.toString());
            return Optional.empty();
        }
    }

    /** The current flag (1 dumpable, 0 not), on Linux; empty elsewhere. */
    public static Optional<Integer> dumpable() {
        if (!linux()) return Optional.empty();
        try {
            return dumpable(prctl());
        } catch (Throwable e) {
            // Handled: unknown off Linux
            return Optional.empty();
        }
    }

    private static Optional<Integer> dumpable(MethodHandle prctl) throws Throwable {
        return Optional.of((int) prctl.invokeExact(PR_GET_DUMPABLE, 0L));
    }

    /** {@code int prctl(int option, ...)}: variadic after the option; one unsigned long given. */
    private static MethodHandle prctl() {
        Linker linker = Linker.nativeLinker();
        return linker.downcallHandle(
                linker.defaultLookup().find("prctl").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG),
                Linker.Option.firstVariadicArg(1));
    }
}
