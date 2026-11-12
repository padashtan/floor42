package dev.floor42.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.event.EventEnvelope;
import dev.floor42.domain.event.WorkbenchEvent.WorkspacePrepared;
import dev.floor42.server.app.OfficeService;
import dev.floor42.server.app.Workspaces;
import dev.floor42.server.journal.Journal;
import dev.floor42.server.security.ReplaySanitizer;
import dev.floor42.server.security.SessionToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A mission's replay as one self-contained HTML file (ADR-0035): the mission's events as the journal holds them, each
 * with its origin, cleaned by {@link ReplaySanitizer}, and a small player written in the file. Nothing is added to
 * the recording: no event, no estimate the journal does not hold. Only the director's explicit request builds it.
 */
@ApplicationScoped
public class ReplayExports {

    /** The sentence every file carries, word for word. */
    static final String STATEMENT = "events as recorded; nothing added";

    static final int FORMAT = 1;

    /** At playback, a longer silence between two events is shortened to this; the file's header says so. */
    static final int MAX_GAP_MS = 2000;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Header(
            String generator, String version, String mission, String exportedAt, String statement, String pacing) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MissionPart(String id, String title, String objective, String origin, String status) {}

    record AgentPart(String id, String name, String role) {}

    record TicketPart(String id, String title) {}

    /** A cost as the journal holds it: {@code basis} says who gave it ("estimated" by Claude Code). */
    record Cost(double usd, String basis) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EventPart(
            int seq,
            String at,
            String origin,
            String actor,
            String type,
            String text,
            String agent,
            String ticket,
            String tool,
            boolean ran,
            String output,
            Boolean outputClipped,
            Cost cost) {}

    /** What the digest covers: the recording, not the moment of the export nor the screen's words. */
    record Body(MissionPart mission, List<AgentPart> agents, List<TicketPart> tickets, List<EventPart> events) {}

    record Data(
            int format,
            Header header,
            MissionPart mission,
            List<AgentPart> agents,
            List<TicketPart> tickets,
            List<EventPart> events,
            int maxGapMs,
            String language,
            Map<String, String> labels) {}

    /** A replay built and cleaned, before it becomes a file. */
    record Built(Body body, ReplaySanitizer sanitizer, String digest) {}

    /** Quarkus's mapper; the replay file leaves absent fields out (see {@link #replayJson}). */
    @Inject
    ObjectMapper json;

    /** The same configuration as every other JSON of the daemon, null fields left out of the file. */
    private volatile ObjectMapper replayJson;

    private ObjectMapper replayJson() {
        ObjectMapper m = replayJson;
        if (m == null) {
            m = json.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
            replayJson = m;
        }
        return m;
    }

    @Inject
    OfficeService office;

    @Inject
    Journal journal;

    @Inject
    Workspaces workspaces;

    @Inject
    SessionToken token;

    @ConfigProperty(name = "floor42.data-dir")
    java.nio.file.Path dataDir;

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    String version() {
        return version;
    }

    /** Reads the mission's events and cleans them; the same input gives the same digest. */
    Built build(String id) {
        MissionId mission = new MissionId(id);
        return office.query(c -> {
            Mission m = c.missions().find(mission).orElseThrow(() -> new NotFoundException("Unknown mission " + id));
            List<EventEnvelope> events = journal.readAll().stream()
                    .filter(e -> mission.equals(e.mission()))
                    .toList();
            ReplaySanitizer clean = new ReplaySanitizer(token.value(), roots(m, events));
            return assemble(c, m, events, clean);
        });
    }

    private Map<String, String> roots(Mission m, List<EventEnvelope> events) {
        Map<String, String> roots = new LinkedHashMap<>();
        if (m.workspace() != null && !m.workspace().isBlank()) roots.put(absolute(m.workspace()), "<repository>");
        workspaces.worktrees(m.id()).forEach((agent, path) -> roots.put(absolute(path.toString()), worktree(agent)));
        for (EventEnvelope e : events) {
            if (e.event() instanceof WorkspacePrepared w && w.path() != null) {
                roots.put(absolute(w.path()), worktree(w.agent().value()));
            }
        }
        for (java.nio.file.Path own : dev.floor42.server.config.DataFolders.guarded(dataDir)) {
            roots.putIfAbsent(absolute(own.toString()), "<floor42-data>");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) roots.putIfAbsent(absolute(home), "<home>");
        return roots;
    }

    private static String worktree(String agent) {
        return "<worktree:" + agent + ">";
    }

    private static String absolute(String path) {
        // A Windows drive path in a journal written there is absolute whatever system exports it: this platform would
        // make it relative to its working directory, and the recorded folder would stay in the text.
        if (path.matches("(?s)[A-Za-z]:[\\\\/].*")) return path.replace('\\', '/');
        try {
            return java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        } catch (java.nio.file.InvalidPathException e) {
            // Handled: a path this platform cannot parse is shown as it was written
            return path;
        }
    }

    private Built assemble(Company c, Mission m, List<EventEnvelope> events, ReplaySanitizer clean) {
        MissionPart mission = new MissionPart(
                m.id().value(),
                clean.text(0, "title", m.title()),
                clean.text(0, "objective", m.objective()),
                m.origin().name(),
                m.status().name());
        List<EventPart> parts = new ArrayList<>();
        Set<String> agents = new LinkedHashSet<>();
        int seq = 0;
        for (EventEnvelope e : events) parts.add(ReplayEvents.part(c, e, ++seq, clean, agents));
        List<AgentPart> people = agents.stream()
                .map(a -> c.staff()
                        .find(new dev.floor42.domain.AgentId(a))
                        .map(x -> new AgentPart(
                                a, clean.text(0, "agent", x.displayName()), clean.text(0, "role", x.role())))
                        .orElse(new AgentPart(a, a, "")))
                .toList();
        List<TicketPart> tickets = c.tickets().of(m.id()).stream()
                .map(t -> new TicketPart(t.id().value(), clean.text(0, "ticket", t.title())))
                .toList();
        Body body = new Body(mission, people, tickets, List.copyOf(parts));
        return new Built(body, clean, digest(body));
    }

    private String digest(Body body) {
        try {
            byte[] bytes = replayJson().writeValueAsBytes(body);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot digest the replay", e);
        }
    }

    /** The preview of what the file will hold and what was masked: what the director confirms. */
    ReplayExportResource.ReplayExportPreview preview(Built b) {
        List<EventPart> events = b.body().events();
        long real = events.stream()
                .filter(e -> e.origin().equals(Origin.REAL.name()))
                .count();
        List<ReplayExportResource.MaskCount> counts = new ArrayList<>();
        b.sanitizer().counts().forEach((kind, n) -> counts.add(new ReplayExportResource.MaskCount(kind.name(), n)));
        List<ReplayExportResource.MaskedItem> details = b.sanitizer().details().stream()
                .map(d -> new ReplayExportResource.MaskedItem(
                        d.event(),
                        d.event() == 0 ? "Mission" : events.get(d.event() - 1).type(),
                        d.field(),
                        d.kind().name()))
                .toList();
        return new ReplayExportResource.ReplayExportPreview(
                b.body().mission().id(),
                b.body().mission().title(),
                b.body().mission().origin(),
                version,
                events.size(),
                (int) real,
                (int) (events.size() - real),
                b.body().agents().stream().map(AgentPart::name).toList(),
                b.body().tickets().size(),
                (int) events.stream().filter(e -> e.output() != null).count(),
                (int) events.stream().filter(e -> e.cost() != null).count(),
                counts,
                details,
                b.sanitizer().total() > details.size(),
                b.digest());
    }

    /** The labels the director's screen sends, at most this many, each this long at most. */
    static final int LABELS_MAX = 80;

    static final int LABEL_LENGTH = 300;

    /** The file: header comment, the provenance line readable without script, the cleaned data, the player. */
    String html(Built b, String language, Map<String, String> labels, Instant exportedAt) {
        Body body = b.body();
        String lang = "fr".equals(language) ? "fr" : "en";
        Header header = new Header(
                "Floor42",
                version,
                body.mission().id(),
                exportedAt.toString(),
                STATEMENT,
                "gaps longer than " + MAX_GAP_MS / 1000 + " s are shortened at playback");
        Data data = new Data(
                FORMAT,
                header,
                body.mission(),
                body.agents(),
                body.tickets(),
                body.events(),
                MAX_GAP_MS,
                lang,
                labels(labels));
        String json;
        try {
            json = forScript(replayJson().writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the replay", e);
        }
        String html = page(body, lang, json, exportedAt);
        // Fail closed: whatever the sanitizer missed, the token never leaves in a file.
        if (html.contains(token.value())) throw new IllegalStateException("The replay would carry the session token");
        return html;
    }

    /** The page: the provenance line readable without script, the cleaned data, the player; offline by construction. */
    private String page(Body body, String lang, String json, Instant exportedAt) {
        String provenance = "Generated by Floor42 version " + version + " · mission "
                + body.mission().id() + " · exported at " + exportedAt + " · " + STATEMENT;
        return "<!DOCTYPE html>\n"
                + "<!-- " + provenance.replace("--", "- -") + " -->\n"
                + "<html lang=\"" + lang + "\">\n<head>\n<meta charset=\"utf-8\">\n"
                // Offline by construction: the file may load nothing, call nothing, send nothing.
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline';"
                + " script-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta name=\"generator\" content=\"Floor42 " + escape(version) + "\">\n"
                + "<title>"
                + escape(body.mission().id() + " · " + body.mission().title()) + "</title>\n"
                + "<style>\n" + resource("replay/player.css") + "</style>\n</head>\n<body>\n"
                + "<p id=\"provenance\">" + escape(provenance) + "</p>\n"
                + "<noscript><p>" + escape("This replay needs JavaScript to play; its data is in this file.")
                + "</p></noscript>\n"
                + "<div id=\"app\"></div>\n"
                + "<script type=\"application/json\" id=\"replay-data\">" + json + "</script>\n"
                + "<script>\n" + resource("replay/player.js") + "</script>\n</body>\n</html>\n";
    }

    private static Map<String, String> labels(Map<String, String> labels) {
        Map<String, String> out = new LinkedHashMap<>();
        if (labels == null) return out;
        labels.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().matches("[A-Za-z][A-Za-z0-9.]{0,39}"))
                .filter(e -> e.getValue() != null)
                .limit(LABELS_MAX)
                .forEach(e -> out.put(
                        e.getKey(),
                        e.getValue().length() > LABEL_LENGTH ? e.getValue().substring(0, LABEL_LENGTH) : e.getValue()));
        return out;
    }

    /** JSON safe inside a script element: no "</script>", no HTML comment, no line separators JavaScript rejects. */
    static String forScript(String json) {
        return json.replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
                .replace(" ", "\\u2028")
                .replace(" ", "\\u2029");
    }

    static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String resource(String name) {
        try (InputStream in = ReplayExports.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
