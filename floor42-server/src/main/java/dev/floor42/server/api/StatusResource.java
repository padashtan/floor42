package dev.floor42.server.api;

import dev.floor42.domain.Origin;
import dev.floor42.server.runtime.ClaudeCodeProbe;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What the daemon can do, public so the mode banner shows even before login: REAL when live agent sessions are
 * available (Claude Code installed and recent enough), SIMULATED otherwise. Each mission still carries its own origin.
 */
@Path("/api/status")
public class StatusResource {

    /**
     * @param unfamiliar kinds of stream line Claude Code sent that Floor42 does not know (its protocol changed), each
     *     with its shape: keys and value types, never the values
     */
    public record Runtime(
            boolean available,
            String version,
            String reason,
            List<dev.floor42.server.runtime.ClaudeCodeProbe.UnfamiliarLine> unfamiliar) {
        public Runtime {
            unfamiliar = unfamiliar == null ? null : Collections.unmodifiableList(new ArrayList<>(unfamiliar));
        }
    }

    /**
     * The shell sandbox (ADR-0028).
     *
     * @param working measured by running a command in it; null while the director has not turned it on
     */
    public record Sandbox(boolean enabled, Boolean working) {}

    /**
     * The commit the daemon was built from, read from the file the build writes; null fields when it was built
     * without a git checkout (a Docker image).
     *
     * @param dirty the checkout had changes not committed when the daemon was built
     */
    public record Build(String commit, String commitFull, String commitTime, Boolean dirty) {}

    /**
     * The daemon's own process (ADR-0044): on Linux and in Docker it makes itself non-dumpable, so processes of its OS
     * user (agents) cannot read its {@code /proc} entries.
     *
     * @param nonDumpableRequested {@code floor42.process.non-dumpable}
     * @param nonDumpable asked of the kernel at this request; null where it cannot be (Windows, macOS)
     * @param attachDisabled the JVM runs with -XX:+DisableAttachMechanism, as it reports it; null when it does not say
     */
    public record Process(boolean nonDumpableRequested, Boolean nonDumpable, Boolean attachDisabled) {}

    public record Status(String version, Build build, Origin mode, Runtime runtime, Sandbox sandbox, Process process) {}

    static final Build BUILD = readBuild();

    /** What the build asked git where it ran (pom profile {@code build-commit}); nothing when it had no checkout. */
    static Build readBuild() {
        var p = new java.util.Properties();
        Boolean dirty = null;
        try (var in = StatusResource.class.getResourceAsStream("/build-commit.properties")) {
            if (in != null) p.load(in);
        } catch (java.io.IOException unreadable) {
            // Handled: No commit known: the fields stay null, never guessed.
        }
        try (var in = StatusResource.class.getResourceAsStream("/build-changes.txt")) {
            if (in != null && p.getProperty("commitFull") != null) dirty = !new String(in.readAllBytes()).isBlank();
        } catch (java.io.IOException unreadable) {
            // Handled: Whether changes were left out is then unknown.
        }
        return new Build(p.getProperty("commit"), p.getProperty("commitFull"), p.getProperty("commitTime"), dirty);
    }

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @Inject
    ClaudeCodeProbe probe;

    @Inject
    dev.floor42.server.runtime.Sandbox sandbox;

    @Inject
    dev.floor42.server.security.ProcessHardening hardening;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Status status() {
        ClaudeCodeProbe.Availability a = probe.availability();
        return new Status(
                version,
                BUILD,
                a.available() ? Origin.REAL : Origin.SIMULATED,
                new Runtime(a.available(), a.version(), a.reason(), probe.unfamiliar()),
                new Sandbox(sandbox.enabled(), sandbox.enabled() ? sandbox.available() : null),
                process());
    }

    private Process process() {
        dev.floor42.server.security.ProcessHardening.State s = hardening.state();
        return new Process(s.requested(), s.nonDumpable(), s.attachDisabled());
    }
}
