package dev.floor42.server.app.delivery;

import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.PreferenceEvent.BreakGlassSignIn;
import dev.floor42.domain.event.PreferenceEvent.UnsafeModeChanged;
import dev.floor42.domain.event.WorkbenchEvent.WorkspacePrepared;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.workspace.Workspaces;
import dev.floor42.server.config.DataFolders;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.ReplaySanitizer;
import dev.floor42.server.security.SessionToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the human oversight report of a mission (ADR-0075): reads the journal, adds the company-wide sign-ins and
 * unsafe-mode switches of the mission's span, cleans every text with the replay export's {@link ReplaySanitizer}.
 * Reading journals nothing.
 */
@ApplicationScoped
public class OversightReports {

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    @Inject
    SessionToken token;

    @Inject
    Workspaces workspaces;

    @ConfigProperty(name = "floor42.data-dir")
    Path dataDir;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    public OversightReport build(String id) {
        MissionId mission;
        try {
            mission = new MissionId(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Unknown mission " + id, e);
        }
        Mission m = office.query(c -> c.missions().find(mission))
                .orElseThrow(() -> new NotFoundException("Unknown mission " + id));
        List<EventEnvelope> all = journal.readAll();
        List<EventEnvelope> own =
                all.stream().filter(e -> mission.equals(e.mission())).toList();
        ReplaySanitizer clean = new ReplaySanitizer(token.value(), roots(m, own));
        return OversightComposer.compose(m, span(all, own), clean, version, Instant.now());
    }

    /** The same report as Markdown (inert agent text). */
    public String markdown(String id) {
        return OversightMarkdown.render(build(id));
    }

    /** The mission's events, with the company-wide sign-ins and unsafe-mode switches between its first and last. */
    static List<EventEnvelope> span(List<EventEnvelope> all, List<EventEnvelope> own) {
        if (own.isEmpty()) return own;
        Instant from = own.getFirst().occurredAt();
        Instant to = own.getLast().occurredAt();
        java.util.Set<String> ids =
                own.stream().map(EventEnvelope::eventId).collect(java.util.stream.Collectors.toSet());
        List<EventEnvelope> merged = new ArrayList<>();
        for (EventEnvelope e : all) {
            boolean company = e.mission() == null
                    && (e.event() instanceof BreakGlassSignIn || e.event() instanceof UnsafeModeChanged)
                    && !e.occurredAt().isBefore(from)
                    && !e.occurredAt().isAfter(to);
            if (company || ids.contains(e.eventId())) merged.add(e);
        }
        return merged;
    }

    /** The folders of this machine and their placeholders, as the replay export names them. */
    private Map<String, String> roots(Mission m, List<EventEnvelope> events) {
        Map<String, String> roots = new LinkedHashMap<>();
        if (m.workspace() != null && !m.workspace().isBlank()) roots.put(absolute(m.workspace()), "<repository>");
        workspaces.worktrees(m.id()).forEach((agent, path) -> roots.put(absolute(path.toString()), worktree(agent)));
        for (EventEnvelope e : events) {
            if (e.event() instanceof WorkspacePrepared w && w.path() != null)
                roots.put(absolute(w.path()), worktree(w.agent().value()));
        }
        for (Path own : DataFolders.guarded(dataDir)) roots.putIfAbsent(absolute(own.toString()), "<floor42-data>");
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) roots.putIfAbsent(absolute(home), "<home>");
        return roots;
    }

    private static String worktree(String agent) {
        return "<worktree:" + agent + ">";
    }

    private static String absolute(String path) {
        // A Windows drive path in a journal written there is absolute whatever system exports it.
        if (path.matches("(?s)[A-Za-z]:[\\\\/].*")) return path.replace('\\', '/');
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (java.nio.file.InvalidPathException e) {
            // Handled: a path this platform cannot parse is shown as it was written
            return path;
        }
    }
}
