package dev.floor42.server.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Agent;
import dev.floor42.domain.AgentSession;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Ticket;
import dev.floor42.domain.TicketStatus;
import dev.floor42.server.runtime.ClaudeCodeRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether an ended session can be continued with Claude Code's {@code --resume} instead of a fresh session (ADR-0038).
 * Read only, never guessed: it is possible only when everything below is measured true, otherwise the next session
 * starts fresh from the journal's brief, which is what Floor42 always did.
 *
 * <ol>
 *   <li>the session is the latest of a developer on a ticket it still carries, on a real mission in progress, and it
 *       ended badly (failed or interrupted);
 *   <li>Floor42 kept its launch record ({@code launch.json}, written since 1.6.0) with the Claude Code session id;
 *   <li>the worktree it ran in is the agent's worktree on the mission, and it still exists;
 *   <li>the tools, the model and the effort are those the next session would get: the same permissions;
 *   <li>Claude Code's own transcript ({@code <claude home>/projects/<worktree as a slug>/<id>.jsonl}) still exists,
 *       says it ran in that worktree, and was not written after the session ended (nothing else touched it since).
 * </ol>
 *
 * Whatever the history holds, every tool call of the continued session still goes through Floor42's hook and the
 * mission's policy as it stands: continuing never carries a permission over.
 */
@ApplicationScoped
public class SessionContinuity {

    /** How long after the recorded end Claude Code may still have written its transcript. */
    static final Duration WRITE_GRACE = Duration.ofMinutes(2);

    /** How many project folders are looked through when the worktree's slug is not where the transcript is. */
    static final int MAX_PROJECT_FOLDERS = 2_000;

    /** Quarkus's mapper: one configuration for the whole daemon. */
    @Inject
    ObjectMapper json;

    /**
     * @param possible every condition holds: the director may choose to continue
     * @param reason why not, as a code ({@code ok} when possible): disabled, notReal, open, notFailed, notProduction,
     *     notLatest, noWorktree, noLaunchRecord, otherWorktree, toolsChanged, modelChanged, noTranscript,
     *     transcriptChanged
     * @param claudeSession the Claude Code session id, when known
     */
    public record Continuity(boolean possible, String reason, String claudeSession) {
        static Continuity no(String reason, String claudeSession) {
            return new Continuity(false, reason, claudeSession);
        }
    }

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @ConfigProperty(name = "floor42.catalogue.claude-home")
    Optional<String> claudeHome;

    /** Off: every session starts fresh, as before 1.6.0. */
    @ConfigProperty(name = "floor42.claude.resume.enabled", defaultValue = "true")
    boolean enabled;

    /** The Claude Code session id of a session record ({@code M-1:<id>}). */
    public static String claudeId(String record) {
        int colon = record.indexOf(':');
        return colon < 0 ? record : record.substring(colon + 1);
    }

    /** Whether this session can be continued now. Reads the journal (given) and files; changes nothing. */
    public Continuity assess(Company c, AgentSession s) {
        String id = claudeId(s.id());
        if (!enabled) return Continuity.no("disabled", id);
        Mission m = c.missions().find(s.mission()).orElse(null);
        if (m == null || m.origin() != Origin.REAL) return Continuity.no("notReal", id);
        if (s.isOpen()) return Continuity.no("open", id);
        if (!Boolean.TRUE.equals(s.failed())) return Continuity.no("notFailed", id);
        String why = production(c, m, s);
        if (why != null) return Continuity.no(why, id);
        String worktree =
                c.workshop().workspace(m.id(), s.agent()).map(w -> w.path()).orElse(null);
        if (worktree == null || !Files.isDirectory(Path.of(worktree))) return Continuity.no("noWorktree", id);
        why = sameLaunch(c, m, s, worktree, id);
        if (why != null) return Continuity.no(why, id);
        Path transcript = transcript(worktree, id);
        if (transcript == null) return Continuity.no("noTranscript", id);
        try {
            Instant written = Files.getLastModifiedTime(transcript).toInstant();
            if (s.endedAt() != null && written.isAfter(s.endedAt().plus(WRITE_GRACE))) {
                return Continuity.no("transcriptChanged", id);
            }
        } catch (IOException e) {
            // Handled: a transcript Floor42 cannot date is not offered for a resume.
            return Continuity.no("noTranscript", id);
        }
        return new Continuity(true, "ok", id);
    }

    /** Why the session is not the latest of its agent on a ticket in production; null when it is. */
    private static String production(Company c, Mission m, AgentSession s) {
        Ticket t = c.tickets().find(s.ticket()).orElse(null);
        if (m.status() != MissionStatus.IN_PROGRESS
                || t == null
                || t.status() != TicketStatus.IN_PROGRESS
                || !s.agent().equals(t.assignee())) return "notProduction";
        boolean latest = c.sessions().of(m.id()).stream()
                .filter(x -> x.agent().equals(s.agent()) && x.ticket().equals(s.ticket()))
                .reduce((a, b) -> b)
                .map(x -> x.id().equals(s.id()))
                .orElse(false);
        return latest ? null : "notLatest";
    }

    /** Why a resume would not run as the session was launched (worktree, tools, model, effort); null when it would. */
    private String sameLaunch(Company c, Mission m, AgentSession s, String worktree, String id) {
        JsonNode launch = launch(m.id().value(), id);
        if (launch == null) return "noLaunchRecord";
        if (!samePath(launch.path("workdir").asText(""), worktree)) return "otherWorktree";
        java.util.Set<String> had = new HashSet<>();
        launch.path("tools").forEach(n -> had.add(n.asText()));
        if (!had.equals(new HashSet<>(Briefs.offeredTools(m, s.ticket())))) return "toolsChanged";
        Agent a = c.staff().find(s.agent()).orElse(null);
        String effort =
                launch.path("effort").isNull() ? null : launch.path("effort").asText(null);
        if (a == null
                || !a.model().equals(launch.path("model").asText(""))
                || !java.util.Objects.equals(a.effort(), effort)) return "modelChanged";
        return null;
    }

    /** The launch record Floor42 kept for this Claude Code session of the mission, or null. */
    JsonNode launch(String mission, String claudeSession) {
        Path sessions = dataDir.resolve(ClaudeCodeRuntime.SESSIONS_DIR);
        if (!Files.isDirectory(sessions)) return null;
        try (Stream<Path> dirs = Files.list(sessions)) {
            for (Path d : dirs.toList()) {
                Path f = d.resolve(ClaudeCodeRuntime.LAUNCH_FILE);
                if (!Files.isRegularFile(f)) continue;
                try {
                    JsonNode n = json.readTree(Files.readString(f));
                    if (claudeSession.equals(n.path("sessionId").asText(null))
                            && mission.equals(n.path("mission").asText(null))) return n;
                } catch (IOException | RuntimeException e) {
                    // Handled: an unreadable record is no record; the next one is read.
                }
            }
        } catch (IOException e) {
            // Handled: sessions Floor42 cannot list hold no launch record (documented: null).
            return null;
        }
        return null;
    }

    /**
     * Claude Code's own transcript of the session, kept under the user level Floor42's sessions use (their
     * {@code CLAUDE*} variables are removed), in a folder named after the working directory; null when it is gone or
     * says it ran elsewhere.
     */
    Path transcript(String worktree, String claudeSession) {
        Path projects = home().resolve("projects");
        String file = claudeSession + ".jsonl";
        if (!claudeSession.matches("[A-Za-z0-9-]+")) return null;
        List<Path> candidates = new ArrayList<>();
        candidates.add(projects.resolve(slug(worktree)).resolve(file));
        if (Files.isDirectory(projects)) {
            try (Stream<Path> dirs = Files.list(projects)) {
                dirs.limit(MAX_PROJECT_FOLDERS).map(d -> d.resolve(file)).forEach(candidates::add);
            } catch (IOException e) {
                // Handled: without the real path, the slug's folder alone is searched.
            }
        }
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) continue;
            String cwd = cwd(p);
            if (cwd == null ? p.getParent().getFileName().toString().equals(slug(worktree)) : samePath(cwd, worktree)) {
                return p;
            }
        }
        return null;
    }

    /** Claude Code's folder name for a working directory: every character but a letter or a digit becomes a dash. */
    static String slug(String path) {
        return path.replaceAll("[^A-Za-z0-9]", "-");
    }

    private Path home() {
        return Path.of(claudeHome.filter(s -> !s.isBlank()).orElse(System.getProperty("user.home") + "/.claude"));
    }

    /** The working directory the transcript records, from its first lines; null when none says. */
    private String cwd(Path transcript) {
        try (BufferedReader r = Files.newBufferedReader(transcript, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 20; i++) {
                String line = r.readLine();
                if (line == null) return null;
                if (!line.contains("\"cwd\"")) continue;
                try {
                    String cwd = json.readTree(line).path("cwd").asText(null);
                    if (cwd != null) return cwd;
                } catch (IOException | RuntimeException e) {
                    // Handled: not JSON, the next line is read.
                }
            }
        } catch (IOException | RuntimeException e) {
            // Handled: an unreadable transcript records no working directory (documented: null).
            return null;
        }
        return null;
    }

    static boolean samePath(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        String x = Path.of(a).toAbsolutePath().normalize().toString();
        String y = Path.of(b).toAbsolutePath().normalize().toString();
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? x.equalsIgnoreCase(y)
                : x.equals(y);
    }
}
