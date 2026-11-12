package dev.floor42.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.floor42.domain.Origin;
import dev.floor42.server.iam.IdentityUnavailableException;
import dev.floor42.server.iam.SessionIdentity;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs a live Claude Code session (ADR-0006), as proven by the runtime spike:
 *
 * <ul>
 *   <li>{@code claude -p} in stream-json mode, in the session's working directory (a git worktree);
 *   <li>{@code --restricted}: none of the director's own settings, hooks or plugins reach the agent, and file tools stay
 *       inside the working directory;
 *   <li>every tool call goes through an HTTP PreToolUse hook answered by the mission policy, fail-closed;
 *   <li>tool results and the session's end are read from the stream and recorded by Floor42, never by the agent.
 * </ul>
 *
 * Session files (settings, transcript, stderr) are kept under the data directory as evidence; they never contain
 * credentials.
 */
@ApplicationScoped
public class ClaudeCodeRuntime implements AgentRuntime {

    static final String HOOK_PATH = "/hooks/pre-tool-use";
    static final String TOKEN_VARIABLE = "FLOOR42_HOOK_TOKEN";
    static final String PROCESS_FILE = SessionProcesses.PROCESS_FILE;
    /**
     * What keeps a session apart from the director's own Claude Code: {@code --restricted} (none of the director's
     * settings, hooks or plugins) and {@code --strict-mcp-config} (Floor42's MCP server only). Shown as is by the skills
     * catalogue (W3); what a session really loaded is read from its init line, not inferred from these.
     */
    public static final List<String> ISOLATION = List.of("--restricted", "--strict-mcp-config");
    /** Where the session's transcripts are kept, under the data directory. */
    public static final String SESSIONS_DIR = "sessions";
    /** How long Claude Code may take to exit once its result is read and its input closed. */
    static final int EXIT_GRACE_SECONDS = 30;

    private static final Logger LOG = Logger.getLogger(ClaudeCodeRuntime.class);

    private final ObjectMapper json;
    private final StreamJsonParser parser;
    private final RunningSessions running;

    @Inject
    ClaudeCodeRuntime(ObjectMapper json, RunningSessions running) {
        this.json = json;
        this.parser = new StreamJsonParser(json);
        this.running = running;
    }

    @Inject
    ClaudeCodeConfig config;

    @Inject
    HookRegistry hooks;

    @Inject
    SessionIdentity identity;

    @Inject
    LocalEndpoint endpoint;

    @Inject
    ClaudeCodeProbe probe;

    @Inject
    CostCap costCap;

    @Inject
    UnnamedLaunches unnamed;

    @Inject
    Sandbox sandbox;

    @Inject
    ContextGauge context;

    /** The skills and agents the director chose for the mission (ADR-0040); none when no implementation is deployed. */
    @Inject
    jakarta.enterprise.inject.Instance<SessionAssets> assets;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @Override
    public SessionReport run(SessionSpec spec, Governor governor) {
        if (spec.origin() != Origin.REAL) throw new IllegalArgumentException("A live session is real by definition");
        SessionTally tally = new SessionTally(
                governor, spec.workdir() == null ? null : Path.of(spec.workdir()), sandbox, spec.readOnlyShell());
        Path dir = sessionDir();
        SessionStream stream = streamOf(spec, tally, governor, dir);
        String token = register(spec, tally, stream);
        if (token == null) return stream.report();
        Process process = null;
        Thread watchdog = null;
        AtomicBoolean stalled = new AtomicBoolean();
        try {
            process = launch(spec, token, dir, stream);
            watchdog = watchdog(spec, process, tally, stalled);
            converse(process, spec, dir, stream, tally);
            finish(process, spec, dir, stream, stalled.get());
        } catch (IOException | InterruptedException | RuntimeException e) {
            // Handled: the session ends as failed and the end is recorded (see failed)
            failed(stream, e);
        } finally {
            cleanUp(spec, watchdog, process, dir, token, stream);
        }
        return stream.report();
    }

    /**
     * Handled: the session ends as failed and the end is recorded, whatever broke (a record that fails, the journal,
     * must not hide it); an interrupt is kept for the caller. A refusal by the domain (a mission suspended, a ticket
     * cancelled) is a decision, not a failure: the dispatcher reads it as such (TaskRunners.run).
     */
    private static void failed(SessionStream stream, Exception e) {
        if (e instanceof dev.floor42.domain.DomainException d) throw d;
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        stream.failed(
                e instanceof IOException
                        ? "could not run Claude Code: " + e.getMessage()
                        : e instanceof InterruptedException
                                ? "interrupted"
                                : "Floor42 could not record the session: " + e.getMessage());
    }

    private SessionStream streamOf(SessionSpec spec, SessionTally tally, Governor governor, Path dir) {
        String cap = capOf(spec);
        SessionStream stream =
                new SessionStream(spec, tally, governor, context, probe, cap, id -> writeLaunch(dir, spec, id, cap));
        stream.launch = dir.getFileName().toString();
        return stream;
    }

    /** The session's hook token (Keycloak's in IAM mode); null, the session failed, when no identity could be had. */
    private String register(SessionSpec spec, SessionTally tally, SessionStream stream) {
        if (tally.startRefusal() != null) { // no sandbox, no real session (ADR-0063)
            stream.failed(tally.startRefusal());
            return null;
        }
        try {
            SessionIdentity.Minted minted = identity.mint(spec, stream.launch);
            stream.issued = minted == null ? null : minted.event();
            return identity.register(hooks, tally, spec, stream.launch, minted);
        } catch (IdentityUnavailableException e) {
            // Handled: no identity, no session (fail closed); the reason goes to the report and the mission
            stream.failed("no identity token for this session: " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // Handled: any other mint failure ends the session the same way; only the type is told (no secret leaks)
            stream.failed("no identity token for this session: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Absolute: the session runs in another working directory and must still find its settings. */
    private Path sessionDir() {
        return dataDir.resolve(SESSIONS_DIR)
                .resolve(UUID.randomUUID().toString())
                .toAbsolutePath();
    }

    /** Whatever happened, no process of this session outlives it, a shell it started included; its token dies. */
    private void cleanUp(
            SessionSpec spec, Thread watchdog, Process process, Path dir, String token, SessionStream stream) {
        if (watchdog != null) watchdog.interrupt();
        if (process != null && process.isAlive()) SessionProcesses.stopTree(process);
        if (process != null) {
            String stoppedBecause = running.ended(spec.agent(), process);
            if (stoppedBecause != null) stream.notes.add(stoppedBecause);
        }
        SessionProcesses.forget(dir);
        hooks.unregister(token);
    }

    /** Stops the session once it shows no sign of life for the session timeout ({@link SessionProcesses#watch}). */
    private Thread watchdog(SessionSpec spec, Process process, SessionTally tally, AtomicBoolean stalled) {
        return Thread.ofVirtual()
                .name("watchdog-" + spec.agent() + "-" + spec.ticket())
                .start(() -> SessionProcesses.watch(process, tally, stalled, config.sessionTimeoutSeconds()));
    }

    /** The session's folder and files, then its process, recorded so a next daemon can stop it after a crash. */
    private Process launch(SessionSpec spec, String token, Path dir, SessionStream stream) throws IOException {
        Files.createDirectories(dir);
        Path settings = writeSettings(dir);
        writeMcpConfig(dir);
        writeLaunch(dir, spec, null, stream.cap());
        List<Path> plugins = assets.isResolvable() ? assets.get().pluginDirs(spec, dir, stream.notes) : List.of();
        Process process = start(spec, settings, token, dir, plugins);
        stream.launched();
        running.started(spec.agent(), spec.mission(), process);
        SessionProcesses.remember(process, dir);
        return process;
    }

    /** Sends the brief, then reads the stream until the result, keeping every line in the transcript. */
    private void converse(Process process, SessionSpec spec, Path dir, SessionStream stream, SessionTally tally)
            throws IOException {
        try (Writer stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader stdout =
                        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                Writer transcript = Files.newBufferedWriter(dir.resolve("transcript.jsonl"))) {
            ObjectNode user = json.createObjectNode().put("type", "user");
            user.putObject("message").put("role", "user").put("content", spec.brief());
            stdin.write(json.writeValueAsString(user) + "\n");
            stdin.flush();
            String line;
            while (stream.end == null && (line = stdout.readLine()) != null) {
                tally.alive();
                transcript.write(line + "\n");
                for (RuntimeEvent event : parser.parse(line)) stream.on(event);
            }
        }
    }

    /**
     * The result is in: Claude Code ends once its input is closed; one that lingers is stopped, children too. A stream
     * that closed without a result ends the session in error, with the reason.
     */
    private void finish(Process process, SessionSpec spec, Path dir, SessionStream stream, boolean stalled)
            throws InterruptedException {
        boolean exited = process.waitFor(EXIT_GRACE_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            SessionProcesses.stopTree(process);
            stream.notes.add("Claude Code did not exit " + EXIT_GRACE_SECONDS
                    + " s after its stream closed; Floor42 stopped it");
        }
        if (stream.end != null) return;
        String reason = stalled
                ? "session stopped by Floor42: no sign of life for " + config.sessionTimeoutSeconds() + " s"
                : exited
                        ? "Claude Code exited with code " + process.exitValue() + " without a result: "
                                + stderrTail(dir)
                        : "session stopped: Claude Code closed its stream without a result";
        stream.failed(reason);
        LOG.warnf("Session for %s on %s failed: %s", spec.agent(), spec.ticket(), reason);
    }

    /** The process and everything it started (a shell command, a build). */
    static void stopTree(Process process) {
        SessionProcesses.stopTree(process);
    }

    /**
     * First of all the start-up observers: the previous daemon's leftovers are stopped before the recovery (LiveStartup,
     * ADR-0047) relaunches anything, so a new session can never be taken for a leftover of the last daemon.
     */
    public static final int STARTUP_PRIORITY = jakarta.interceptor.Interceptor.Priority.LIBRARY_BEFORE;

    void onStart(@Observes @jakarta.annotation.Priority(STARTUP_PRIORITY) StartupEvent ignored) {
        stopLeftovers();
    }

    /** Stops the Claude Code processes a previous daemon left running ({@link SessionProcesses#stopLeftovers}). */
    int stopLeftovers() {
        unnamed.scan(dataDir.resolve(SESSIONS_DIR));
        return SessionProcesses.stopLeftovers(dataDir.resolve(SESSIONS_DIR));
    }

    private Process start(SessionSpec spec, Path settings, String token, Path dir, List<Path> plugins)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(commandLine(spec, settings, plugins))
                .directory(Path.of(spec.workdir()).toFile());
        // The agent must not inherit the daemon's own environment markers, nor any Claude session it was started from.
        builder.environment().keySet().removeIf(k -> k.startsWith("CLAUDE") || k.equals("AI_AGENT"));
        builder.environment().put(TOKEN_VARIABLE, token);
        // Claude Code must wait for a question's answer as long as Floor42 lets the director take.
        builder.environment().put("MCP_TOOL_TIMEOUT", Long.toString((config.questionTimeoutSeconds() + 60) * 1000L));
        builder.redirectError(dir.resolve("stderr.log").toFile());
        Process process = builder.start();
        hooks.bindProcess(token, process.pid());
        return process;
    }

    private List<String> commandLine(SessionSpec spec, Path settings, List<Path> plugins) {
        // The session runs in its own working directory: command elements naming files are resolved beforehand.
        List<String> cmd = new ArrayList<>(config.command().stream()
                .map(c -> Files.isRegularFile(Path.of(c))
                        ? Path.of(c).toAbsolutePath().toString()
                        : c)
                .toList());
        cmd.addAll(List.of("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"));
        cmd.addAll(ISOLATION);
        cmd.addAll(List.of(
                "--settings",
                settings.toString(),
                // Floor42's own MCP server only: --strict-mcp-config keeps the director's servers away (ADR-0027).
                "--mcp-config",
                settings.resolveSibling(MCP_FILE).toString(),
                "--tools",
                String.join(",", spec.tools()),
                "--model",
                spec.model()));
        if (spec.effort() != null && !spec.effort().isBlank()) cmd.addAll(List.of("--effort", spec.effort()));
        // Only what the director chose for the mission, laid out by Floor42 in the session's folder (ADR-0040).
        cmd.addAll(pluginArgs(plugins));
        // The cost cap is Claude Code's own: it stops the session once a turn passes it (ADR-0025).
        cmd.addAll(List.of("--max-budget-usd", capOf(spec)));
        // Continuing an earlier session (ADR-0038): its history, under a new id, so the journal never mixes the two.
        if (spec.resume() != null) cmd.addAll(List.of("--resume", spec.resume(), "--fork-session"));
        return cmd;
    }

    /** What Floor42 started a session with, kept beside its transcript: the base for continuing it safely. */
    public static final String LAUNCH_FILE = "launch.json";

    /**
     * Records the session's launch: mission, ticket, agent, worktree, model, effort and tools, and the Claude Code
     * session id once its init line gave it. Read by {@code SessionContinuity}; never holds a credential.
     */
    void writeLaunch(Path dir, SessionSpec spec, String sessionId, String cap) {
        ObjectNode n = json.createObjectNode();
        n.put("mission", spec.mission().value());
        n.put("ticket", spec.ticket() == null ? null : spec.ticket().value());
        n.put("agent", spec.agent().value());
        n.put("workdir", spec.workdir());
        n.put("model", spec.model());
        n.put("effort", spec.effort());
        var tools = n.putArray("tools");
        spec.tools().forEach(tools::add);
        n.put("resume", spec.resume());
        n.put("sessionId", sessionId);
        // A daemon that stops before the init line leaves no session in the journal: the next one counts this cap.
        n.put("capUsd", Double.valueOf(cap));
        try {
            Files.writeString(dir.resolve(LAUNCH_FILE), json.writeValueAsString(n));
        } catch (IOException e) {
            // Handled: logged; only the launch record is missing
            LOG.warnf("Cannot record the launch of session %s: %s", dir.getFileName(), e.getMessage());
        }
    }

    /** One {@code --plugin-dir} per folder: the flag takes one path each time. */
    static List<String> pluginArgs(List<Path> plugins) {
        List<String> out = new ArrayList<>();
        for (Path p : plugins)
            out.addAll(List.of("--plugin-dir", p.toAbsolutePath().toString()));
        return out;
    }

    static final String MCP_FILE = "mcp.json";
    static final String MCP_PATH = "/mcp";

    /** Floor42's MCP server, reached with the session's own token, expanded by Claude Code from its environment. */
    void writeMcpConfig(Path dir) throws IOException {
        ObjectNode config = json.createObjectNode();
        ObjectNode office = config.putObject("mcpServers").putObject("floor42");
        office.put("type", "http").put("url", endpoint.url(MCP_PATH));
        office.putObject("headers").put("Authorization", "Bearer ${" + TOKEN_VARIABLE + "}");
        Files.writeString(
                dir.resolve(MCP_FILE), json.writerWithDefaultPrettyPrinter().writeValueAsString(config));
    }

    /** The session's cap in USD, as Claude Code reads it. */
    String capOf(SessionSpec spec) {
        double cap = costCap.sessionCapUsd(spec.mission());
        if (spec.capUsd() != null) cap = Math.min(cap, spec.capUsd());
        return java.math.BigDecimal.valueOf(cap)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * The hooks (fail-closed, authenticated, with Claude Code's own timeout) and the one setting that only tightens:
     * no skill runs a shell command when it loads.
     */
    Path writeSettings(Path dir) throws IOException {
        ObjectNode hook = json.createObjectNode()
                .put("type", "http")
                .put("url", endpoint.url(HOOK_PATH))
                .put("timeout", config.hookTimeoutSeconds())
                .put("onFailure", "block");
        hook.putObject("headers").put("Authorization", "Bearer $" + TOKEN_VARIABLE);
        hook.putArray("allowedEnvVars").add(TOKEN_VARIABLE);
        ObjectNode settings = json.createObjectNode();
        // A skill's ! line would run on the host past the sandbox (measured 2026-11-11, ADR-0028): never run.
        settings.put("disableSkillShellExecution", true);
        ObjectNode group = settings.putObject("hooks").putArray("PreToolUse").addObject();
        group.put("matcher", "*");
        group.putArray("hooks").add(hook);
        Path file = dir.resolve("settings.json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
        return file;
    }

    private static String stderrTail(Path dir) {
        try {
            String err = Files.readString(dir.resolve("stderr.log"));
            return StreamJsonParser.abbreviate(err.substring(Math.max(0, err.length() - 400)), 300);
        } catch (IOException e) {
            // Handled: the report says there was no stderr to show
            return "(no stderr)";
        }
    }
}
