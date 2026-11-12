package dev.floor42.server.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Actor;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.TerminalEvent.TerminalOpened;
import dev.floor42.server.runtime.pty.Pty;
import dev.floor42.server.security.LogSafe;
import dev.floor42.server.security.SessionToken;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The director's own Claude Code in a terminal (ADR-0041). Not an agent: no Floor42 isolation flag, no hook, no policy,
 * no brief; it runs with the director's rights and settings in the chosen project. What Floor42 does:
 *
 * <ul>
 *   <li>starts it only when the director asks ({@link #open}), one at a time per project, and never types anything into
 *       it but what the director's browser sends;
 *   <li>keeps Floor42's own credentials out of its environment (the director's token, agents' credentials, every
 *       {@code FLOOR42_*} and {@code OFFICE_*} variable);
 *   <li>journals the facts "opened" and "closed", never what is typed or printed: the screen lives in memory only, for
 *       a browser that reattaches;
 *   <li>shows the cost Claude Code itself reports through its status line (an estimate of Claude Code's, labelled so);
 *   <li>stops the program and every process it started when the director closes it, or when the daemon stops.
 * </ul>
 */
@ApplicationScoped
public class DirectorTerminals {

    private static final Logger LOG = Logger.getLogger(DirectorTerminals.class);
    static final String FOLDER = "terminals";
    /** How long a ticket to attach a browser to a terminal stays valid, once. */
    static final long TICKET_SECONDS = 60;

    /**
     * A terminal as the API shows it.
     *
     * @param costUsd what Claude Code last reported for the session through its status line (its own estimate), null
     *     while it reported nothing
     * @param exitCode null while running, or when the system did not tell
     */
    public record TerminalView(
            String id,
            String project,
            String openedAt,
            boolean running,
            Double costUsd,
            String costAt,
            String model,
            Integer exitCode) {}

    /** What a browser attached to a terminal receives. */
    public interface Sink {
        void output(byte[] bytes);

        void status(TerminalView view);

        void ended(TerminalView view);
    }

    @Inject
    OfficeService office;

    @Inject
    TerminalConfig config;

    @Inject
    SessionToken token;

    @Inject
    Clock clock;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    /** Quarkus's mapper: one configuration for the whole daemon. */
    @Inject
    ObjectMapper json;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, LiveTerminal> live = new ConcurrentHashMap<>();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    /** Terminal id to the id of the director's session that opened it; none for the director's own token. */
    private final Map<String, String> openedBy = new ConcurrentHashMap<>();

    private ScheduledExecutorService poller;

    private record Ticket(String terminal, Instant expires) {}

    void start(@Observes StartupEvent ignored) {
        // A terminal the journal says is open did not survive the daemon that opened it.
        office.query(c -> c.terminals().openTerminals())
                .keySet()
                .forEach(id -> record(id, Actor.system(), "interrupted", null));
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "terminal-status");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::readStatuses, 1, 1, TimeUnit.SECONDS);
    }

    void stop(@Observes ShutdownEvent ignored) {
        if (poller != null) poller.shutdownNow();
        for (LiveTerminal l : List.copyOf(live.values())) {
            if (l.recorded.compareAndSet(false, true)) record(l.id, Actor.system(), "interrupted", null);
            l.pty.close();
        }
    }

    /** Why no terminal can be opened here, empty when one can. */
    public Optional<String> unavailable() {
        return Pty.unavailable();
    }

    /** Ends every shell the director left running: signing out everywhere leaves no program of theirs behind. */
    public void closeAll() {
        closeEach(List.copyOf(live.keySet()));
    }

    /**
     * Ends the shells one session opened: signing that browser out stops them, and leaves running those another
     * session of the director's opened.
     */
    public void closeOpenedBy(String session) {
        if (session == null) return;
        closeEach(openedBy.entrySet().stream()
                .filter(e -> e.getValue().equals(session))
                .map(Map.Entry::getKey)
                .toList());
    }

    /** The ids of the sessions that opened a shell still running. */
    public java.util.Set<String> openers() {
        return java.util.Set.copyOf(openedBy.values());
    }

    private void closeEach(List<String> ids) {
        for (String id : ids) {
            try {
                close(id);
            } catch (jakarta.ws.rs.NotFoundException gone) {
                // Handled: it ended by itself meanwhile.
            }
        }
    }

    /** The terminals open now, oldest first. */
    public List<TerminalView> list() {
        return live.values().stream()
                .sorted(java.util.Comparator.comparing(l -> l.openedAt))
                .map(LiveTerminal::view)
                .toList();
    }

    public Optional<TerminalView> find(String id) {
        return Optional.ofNullable(live.get(id)).map(LiveTerminal::view);
    }

    /**
     * Starts the director's own Claude Code in {@code project}: on the director's click only. Refused while one is open
     * there (409 {@code TERMINAL_OPEN}).
     *
     * @param session the id of the director's session asking, null for the director's own token: that session's
     *     sign-out stops this terminal
     */
    public synchronized TerminalView open(String project, int columns, int rows, String session) {
        if (project == null || project.isBlank()) throw RefusedException.invalid("MISSING_PROJECT", "Name a project");
        Optional<String> missing = Pty.unavailable();
        if (missing.isPresent()) throw RefusedException.conflict("TERMINAL_UNAVAILABLE", missing.get());
        Path dir = projectFolder(project);
        String key = dir.toString();
        refuseASecondOn(key);
        Path folder = dataDir.resolve(FOLDER).resolve(UUID.randomUUID().toString());
        List<String> command = prepare(folder, dir);
        Pty pty;
        try {
            pty = Pty.start(command, dir, environmentFor(System.getenv(), token.value()), columns, rows);
        } catch (IOException e) {
            throw RefusedException.conflict(
                    "TERMINAL_FAILED", "Cannot start " + command.getFirst() + ": " + e.getMessage(), e);
        }
        LiveTerminal l = journaled(key, pty, folder.resolve(TerminalStatusLine.STATUS));
        live.put(l.id, l);
        if (session != null) openedBy.put(l.id, session);
        Thread.ofPlatform().daemon().name("terminal-" + l.id + "-read").start(l::pump);
        Thread.ofPlatform().daemon().name("terminal-" + l.id + "-wait").start(l::watch);
        LOG.infof(
                "Terminal %s opened on %s (pid %d): the director's own Claude Code",
                l.id, key, pty.process().pid());
        return l.view();
    }

    private static Path projectFolder(String project) {
        Path dir;
        try {
            dir = Path.of(project.strip()).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw RefusedException.invalid("UNKNOWN_FOLDER", "No folder " + project, e);
        }
        if (!Files.isDirectory(dir)) throw RefusedException.invalid("UNKNOWN_FOLDER", "Not a folder: " + project);
        return dir;
    }

    /** One terminal per project: in memory, then by the journal's own rule, checked before anything starts. */
    private void refuseASecondOn(String key) {
        for (LiveTerminal l : live.values()) {
            if (l.project.equals(key)) {
                throw RefusedException.conflict("TERMINAL_OPEN", "Terminal " + l.id + " is already open on " + key);
            }
        }
        try {
            office.query(c -> {
                if (c.terminals().openTerminals().containsValue(key)) {
                    throw new DomainException("TERMINAL_OPEN", "A terminal is already open on " + key);
                }
                return null;
            });
        } catch (DomainException e) {
            throw RefusedException.conflict(e.code(), e.getMessage(), e);
        }
    }

    /** The terminal's folder and the command line: the director's Claude Code, with the status line that reads cost. */
    private List<String> prepare(Path folder, Path dir) {
        List<String> command = new ArrayList<>(config.command());
        try {
            Files.createDirectories(folder);
            if (config.costFromStatusLine()) {
                Path settings = TerminalStatusLine.write(json, folder, claudeHome(), dir);
                command.addAll(List.of("--settings", settings.toString()));
            }
        } catch (IOException e) {
            throw RefusedException.conflict("TERMINAL_FAILED", "Cannot prepare the terminal: " + e.getMessage(), e);
        }
        return command;
    }

    /** Journals the opening; a terminal the journal refused is stopped at once. */
    private LiveTerminal journaled(String key, Pty pty, Path status) {
        String id;
        try {
            var events = office.execute(c -> c.terminals().open(office.context(Actor.director(), Origin.REAL), key));
            id = ((TerminalOpened) events.getFirst().event()).terminal();
        } catch (RuntimeException e) {
            pty.close();
            throw e;
        }
        int replay = Math.max(4096, config.replayBytes());
        return new LiveTerminal(id, key, clock.instant(), pty, status, replay, json, clock, code -> exited(id, code));
    }

    /**
     * The program ended by itself. It leaves the open terminals before its end is journaled, as {@link #close} does:
     * whoever reads the end in the journal then finds it gone from the API (a 404 on close, absent from the list).
     */
    private void exited(String id, Integer code) {
        LiveTerminal l = forget(id);
        if (l != null && l.recorded.compareAndSet(false, true)) record(id, Actor.system(), "exited", code);
    }

    /** Takes a terminal out of the open ones, once: the caller that gets it records its end. */
    private LiveTerminal forget(String id) {
        LiveTerminal l = live.remove(id);
        openedBy.remove(id);
        return l;
    }

    /** A single-use ticket, valid for a minute, that opens the terminal's WebSocket; it comes with the director's call. */
    public String ticket(String id) {
        if (!live.containsKey(id)) throw new jakarta.ws.rs.NotFoundException("No open terminal " + id);
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String ticket = HexFormat.of().formatHex(bytes);
        tickets.entrySet().removeIf(e -> e.getValue().expires.isBefore(clock.instant()));
        tickets.put(ticket, new Ticket(id, clock.instant().plusSeconds(TICKET_SECONDS)));
        return ticket;
    }

    /** The terminal a ticket opens, once; empty for a ticket unknown, used or expired, or another terminal's. */
    public Optional<String> redeem(String id, String ticket) {
        if (ticket == null) return Optional.empty();
        Ticket t = tickets.remove(ticket);
        if (t == null || t.expires.isBefore(clock.instant()) || !t.terminal.equals(id)) return Optional.empty();
        return live.containsKey(id) ? Optional.of(id) : Optional.empty();
    }

    /** Attaches a browser: it first gets what the screen shows (kept in memory), then everything new. */
    public AutoCloseable attach(String id, Sink sink) {
        LiveTerminal l = live.get(id);
        if (l == null) throw new jakarta.ws.rs.NotFoundException("No open terminal " + id);
        synchronized (l) {
            byte[] screen = l.replay();
            if (screen.length > 0) sink.output(screen);
            l.sinks.add(sink);
        }
        sink.status(l.view());
        return () -> l.sinks.remove(sink);
    }

    /** What the director typed or pasted, passed on unchanged. */
    public void input(String id, byte[] bytes) throws IOException {
        LiveTerminal l = live.get(id);
        if (l == null) throw new IOException("No open terminal " + id);
        l.pty.write(bytes);
    }

    public void resize(String id, int columns, int rows) throws IOException {
        LiveTerminal l = live.get(id);
        if (l != null) l.pty.resize(columns, rows);
    }

    /** The director closes the terminal: the program and every process it started are stopped. */
    public TerminalView close(String id) {
        LiveTerminal l = forget(id);
        if (l == null) throw new jakarta.ws.rs.NotFoundException("No open terminal " + id);
        try {
            if (l.recorded.compareAndSet(false, true)) record(id, Actor.director(), "closed", null);
        } finally {
            // Out of the open terminals already: whatever the record threw, nothing else would stop it.
            l.pty.close();
        }
        return l.view();
    }

    /** The environment the director's Claude Code gets ({@link TerminalEnvironment}). */
    public static Map<String, String> environmentFor(Map<String, String> daemon, String directorToken) {
        return TerminalEnvironment.of(daemon, directorToken);
    }

    private Path claudeHome() {
        if (config.claudeHome().isPresent()) return Path.of(config.claudeHome().get());
        String configured = System.getenv("CLAUDE_CONFIG_DIR");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".claude");
    }

    private void record(String id, Actor actor, String reason, Integer exitCode) {
        try {
            office.execute(c -> c.terminals().close(office.context(actor, Origin.REAL), id, reason, exitCode));
        } catch (RuntimeException e) {
            // Handled: logged; the next start records the terminal as interrupted
            LOG.warnf("Cannot record that terminal %s closed: %s", LogSafe.of(id), LogSafe.of(e.getMessage()));
        }
    }

    private void readStatuses() {
        for (LiveTerminal l : live.values()) {
            try {
                l.readStatus();
            } catch (RuntimeException e) {
                // Handled: logged; read again a second later
                LOG.debugf("Status of terminal %s unreadable: %s", l.id, e);
            }
        }
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
