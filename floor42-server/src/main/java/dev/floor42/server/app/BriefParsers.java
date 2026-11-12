package dev.floor42.server.app;

import dev.floor42.domain.Committee;
import dev.floor42.domain.ConsultationKind;
import dev.floor42.domain.Lesson;
import dev.floor42.domain.ProposedTicket;
import dev.floor42.domain.StaffingReview;
import dev.floor42.domain.WorkRole;
import dev.floor42.server.runtime.SandboxToolkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Floor42 reads back from an agent's answer: its marker lines ({@code PROPOSAL:}, {@code TICKET:}...). Each
 * parser is a pure function of the text; a line may be decorated (Markdown bullets, bold) and the key's case does not
 * matter. A missing line reads as nothing, never as a guess.
 */
final class BriefParsers {

    private static final Pattern SUMMARY_LINE =
            markers("PROPOSAL|POSITION|CRITICAL OBJECTION|RECOMMENDATION|QUESTIONS", true);
    private static final Pattern CLARIFY_LINE = markers("CLARIFY|CONTEXT|OPTIONS|RECOMMENDED", false);
    private static final Pattern PLAN_LINE = markers("TICKET|WHY|DONE WHEN|DEPENDS ON|SIZE", false);
    private static final Pattern GRAPH_LINE = markers("TICKET|WHY|DONE WHEN|DEPENDS ON|SIZE|TEST|RISK|TIER", false);
    private static final Pattern HELP_LINE = markers("CONSULT|RESEARCH", true);
    private static final Pattern RETRO_LINE = markers("WENT WELL|WENT BADLY|CONCLUSION|LESSON", false);
    private static final Pattern STAFFING_LINE = markers("VERDICT|HIRE|PARALLEL", false);
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final String NONE = "none\\.?|no|n/a|-";

    private BriefParsers() {}

    /** A marker line: up to four decorations, the key, a colon, then its value (non-empty when {@code filled}). */
    private static Pattern markers(String keys, boolean filled) {
        return Pattern.compile("(?im)^\\W{0,4}(" + keys + ")\\W{0,4}:\\s*(" + (filled ? ".+?" : ".*?") + ")\\s*$");
    }

    /** One marker line: its key upper-cased, its value without Markdown bold. */
    private record Line(String key, String value) {}

    private static List<Line> lines(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        List<Line> out = new ArrayList<>();
        while (m.find())
            out.add(new Line(
                    m.group(1).toUpperCase(Locale.ROOT),
                    m.group(2).replace("**", "").strip()));
        return out;
    }

    private static boolean says(String value, String pattern) {
        return value.toLowerCase(Locale.ROOT).matches(pattern);
    }

    private static List<String> split(String value, String separator) {
        return Arrays.stream(value.split(separator))
                .map(String::strip)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    // ---- committee ----------------------------------------------------------------------------------------------

    /** The last occurrence of each closing line wins; "none" means no critical objection and no question. */
    static Briefs.Summary summary(String text) {
        Map<String, String> found = new HashMap<>();
        for (Line l : lines(SUMMARY_LINE, text)) found.put(l.key(), l.value());
        String objection = found.get("CRITICAL OBJECTION");
        if (objection != null && says(objection, NONE)) objection = null;
        String asked = found.get("QUESTIONS");
        List<String> questions = asked == null || says(asked, NONE) ? List.of() : split(asked, "\\|\\|");
        return new Briefs.Summary(
                found.get("PROPOSAL"), found.get("POSITION"), objection, found.get("RECOMMENDATION"), questions);
    }

    /**
     * The business analyst's questions for the director, read from its blocks of lines: CLARIFY, then optionally
     * CONTEXT, OPTIONS (separated by "|", or none for a free answer) and RECOMMENDED. Lines before the first CLARIFY
     * are ignored.
     */
    static List<Committee.Clarification> clarifications(String text) {
        List<Committee.Clarification> out = new ArrayList<>();
        Committee.Clarification current = null;
        for (Line l : lines(CLARIFY_LINE, text)) {
            if (l.key().equals("CLARIFY")) {
                if (current != null) out.add(current);
                current = l.value().isEmpty() ? null : new Committee.Clarification(l.value(), null, List.of(), null);
            } else if (current != null) {
                current = clarified(current, l);
            }
        }
        if (current != null) out.add(current);
        return out;
    }

    private static Committee.Clarification clarified(Committee.Clarification q, Line l) {
        String v = l.value();
        return switch (l.key()) {
            case "CONTEXT" ->
                new Committee.Clarification(q.question(), v.isEmpty() ? null : v, q.options(), q.recommendation());
            case "OPTIONS" ->
                new Committee.Clarification(
                        q.question(),
                        q.context(),
                        says(v, "none\\.?|-|") ? List.of() : split(v, "\\|"),
                        q.recommendation());
            default ->
                new Committee.Clarification(
                        q.question(), q.context(), q.options(), v.isEmpty() || says(v, "none\\.?|-") ? null : v);
        };
    }

    // ---- planning -----------------------------------------------------------------------------------------------

    /** A ticket being read: its lines until the next TICKET line. {@code notes} holds TEST, RISK and TIER (graph mode). */
    private record Draft(
            String title, String why, String size, List<String> done, List<Integer> deps, Map<String, String> notes) {

        Draft(String title) {
            this(title, "", "", List.of(), List.of(), Map.of());
        }

        Draft with(Line l, int index, boolean extend) {
            return switch (l.key()) {
                case "WHY" -> new Draft(title, l.value(), size, done, deps, notes);
                case "SIZE" -> new Draft(title, why, l.value(), done, deps, notes);
                case "DONE WHEN" -> new Draft(title, why, size, criteria(l.value()), deps, notes);
                case "DEPENDS ON" ->
                    extend
                            ? extending(l.value(), index)
                            : new Draft(title, why, size, done, dependencies(l.value(), index), notes);
                default -> new Draft(title, why, size, done, deps, noted(l));
            };
        }

        /** A graph extension (ADR-0062) may cite the T-ids of nodes already there; the rest are earlier proposals. */
        private Draft extending(String value, int index) {
            Map<String, String> out = new HashMap<>(notes);
            out.put(
                    EXISTING,
                    TICKET_ID
                            .matcher(value)
                            .results()
                            .map(r -> "T-" + r.group(1))
                            .collect(java.util.stream.Collectors.joining(",")));
            return new Draft(
                    title,
                    why,
                    size,
                    done,
                    dependencies(TICKET_ID.matcher(value).replaceAll(""), index),
                    out);
        }

        private Map<String, String> noted(Line l) {
            Map<String, String> out = new HashMap<>(notes);
            out.put(l.key(), l.value());
            return out;
        }

        ProposedTicket ticket() {
            String description = size.isBlank() ? why : (why + " (size " + size + ")").strip();
            return new ProposedTicket(title, description, done, deps, existing());
        }

        private List<dev.floor42.domain.TicketId> existing() {
            String ids = notes.getOrDefault(EXISTING, "");
            return ids.isBlank()
                    ? List.of()
                    : Arrays.stream(ids.split(","))
                            .map(dev.floor42.domain.TicketId::new)
                            .distinct()
                            .toList();
        }

        /** Graph mode (ADR-0062): the TEST is one more criterion, RISK and TIER are lines of the description. */
        ProposedTicket node() {
            List<String> criteria = new ArrayList<>(done);
            String test = notes.getOrDefault("TEST", "");
            if (!test.isBlank() && !says(test, NONE)) criteria.add("Test: " + test);
            List<String> lines = new ArrayList<>();
            lines.add(ticket().description());
            Matcher risk = RISK.matcher(notes.getOrDefault("RISK", ""));
            if (risk.find()) lines.add("Risk: " + risk.group(1).toLowerCase(Locale.ROOT) + risk.group(2));
            boolean light =
                    notes.getOrDefault("TIER", "").toLowerCase(Locale.ROOT).startsWith("light");
            lines.add("Tier: " + (light ? "light" : "standard"));
            return new ProposedTicket(title, String.join("\n", lines).strip(), criteria, deps, existing());
        }
    }

    private static final String EXISTING = "EXISTING";
    private static final Pattern TICKET_ID = Pattern.compile("\\bT-(\\d+)\\b");
    private static final Pattern RISK = Pattern.compile("(?i)^(low|high)\\b(.*)$");

    /**
     * The tickets of a planner's report, in order. A ticket starts at each TICKET line; dependencies are the numbers
     * of earlier tickets (1-based in the report), anything else is ignored.
     */
    static List<ProposedTicket> plan(String text) {
        return plan(text, false);
    }

    /**
     * As {@link #plan(String)}; in graph mode (ADR-0062) the TEST, RISK and TIER lines are read too, and only then: a
     * loop brief never asks for them, so a loop plan reads as it always did.
     */
    static List<ProposedTicket> plan(String text, boolean graph) {
        return plan(text, graph, false);
    }

    /** As above; {@code extend} reads the T-ids a graph extension builds on (ADR-0062) beside the numbers. */
    static List<ProposedTicket> plan(String text, boolean graph, boolean extend) {
        List<ProposedTicket> tickets = new ArrayList<>();
        Draft draft = null;
        for (Line l : lines(graph ? GRAPH_LINE : PLAN_LINE, text)) {
            if (l.key().equals("TICKET")) {
                if (draft != null) tickets.add(graph ? draft.node() : draft.ticket());
                draft = new Draft(l.value());
            } else if (draft != null) {
                draft = draft.with(l, tickets.size(), extend);
            }
        }
        if (draft != null) tickets.add(graph ? draft.node() : draft.ticket());
        return tickets.stream().filter(t -> !t.title().isBlank()).toList();
    }

    private static final Pattern NEEDS_LINE = markers("TICKET|NEEDS", false);

    /**
     * The tools each ticket of a planner's report NEEDS, by position, aligned with {@link #plan}: a ticket without the
     * line, or with "none", needs nothing; the last NEEDS line of a ticket wins. Names are made plain by {@link
     * SandboxToolkit#normalize}; what is not a command name is left out.
     */
    static List<List<String>> toolNeeds(String text) {
        List<List<String>> out = new ArrayList<>();
        String title = null;
        List<String> needs = List.of();
        for (Line l : lines(NEEDS_LINE, text)) {
            if (l.key().equals("TICKET")) {
                if (title != null && !title.isBlank()) out.add(needs);
                title = l.value();
                needs = List.of();
            } else if (title != null) {
                needs = split(l.value(), "[,;]").stream()
                        .map(SandboxToolkit::normalize)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
            }
        }
        if (title != null && !title.isBlank()) out.add(needs);
        return out;
    }

    /** The numbers of earlier tickets, 0-based, among the {@code earlier} already read. */
    private static List<Integer> dependencies(String value, int earlier) {
        return NUMBER.matcher(value)
                .results()
                .map(r -> Integer.parseInt(r.group()) - 1)
                .filter(d -> d >= 0 && d < earlier)
                .distinct()
                .toList();
    }

    /** Criteria are separated by semicolons, but not inside brackets or quotes, where planners use them too. */
    static List<String> criteria(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (char ch : value.toCharArray()) {
            if (ch == '"') quoted = !quoted;
            else if (!quoted && (ch == '(' || ch == '[')) depth++;
            else if (!quoted && (ch == ')' || ch == ']') && depth > 0) depth--;
            if (ch == ';' && depth == 0 && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString());
        return out.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ---- review -------------------------------------------------------------------------------------------------

    /** The reviewer's last CONSULT or RESEARCH line, if any; a consultation names a role before its question. */
    static Optional<Briefs.Help> help(String text) {
        Briefs.Help found = null;
        for (Line l : lines(HELP_LINE, text)) found = help(l).orElse(found);
        return Optional.ofNullable(found);
    }

    private static Optional<Briefs.Help> help(Line l) {
        String value = l.value();
        if (l.key().equals("RESEARCH")) return Optional.of(new Briefs.Help(ConsultationKind.RESEARCH, null, value));
        int colon = value.indexOf(':');
        if (colon <= 0 || colon >= value.length() - 1) return Optional.empty();
        return Optional.of(new Briefs.Help(
                ConsultationKind.CONSULT,
                value.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                value.substring(colon + 1).strip()));
    }

    // ---- retrospective ------------------------------------------------------------------------------------------

    /** The lines of a retrospective answer starting with this marker, in order, without the marker. */
    static List<String> retroLines(String text, String marker) {
        return lines(RETRO_LINE, text).stream()
                .filter(l -> l.key().equalsIgnoreCase(marker) && !l.value().isEmpty())
                .map(Line::value)
                .toList();
    }

    /** The chair's conclusions and lessons; a lesson's role prefix is read when it names a role, else it is for all. */
    static Briefs.RetroConclusion retroConclusion(String text) {
        List<Lesson.Proposal> lessons = retroLines(text, "LESSON").stream()
                .map(BriefParsers::lesson)
                .flatMap(Optional::stream)
                .toList();
        return new Briefs.RetroConclusion(retroLines(text, "CONCLUSION"), lessons);
    }

    private static Optional<Lesson.Proposal> lesson(String line) {
        int colon = line.indexOf(':');
        String head = colon < 0 ? "" : line.substring(0, colon).strip().toUpperCase(Locale.ROOT);
        WorkRole role = Arrays.stream(WorkRole.values())
                .filter(r -> r.name().equals(head))
                .findFirst()
                .orElse(null);
        boolean named = role != null || head.equals("ALL");
        String what = named ? line.substring(colon + 1).strip() : line;
        return what.isEmpty() ? Optional.empty() : Optional.of(new Lesson.Proposal(role, what));
    }

    // ---- staffing -----------------------------------------------------------------------------------------------

    /** What the planner advised: the last VERDICT and PARALLEL lines win, HIRE lines add up to the most allowed. */
    static Briefs.StaffingAdvice staffingAdvice(String text) {
        boolean worthIt = false;
        Integer parallel = null;
        List<StaffingReview.Hire> hires = new ArrayList<>();
        for (Line l : lines(STAFFING_LINE, text)) {
            switch (l.key()) {
                case "VERDICT" -> worthIt = l.value().toUpperCase(Locale.ROOT).startsWith("HIRE");
                case "PARALLEL" -> parallel = parallel(l.value()).orElse(parallel);
                default ->
                    hire(l.value())
                            .filter(h -> hires.size() < StaffingReview.MAX_HIRES)
                            .ifPresent(hires::add);
            }
        }
        return new Briefs.StaffingAdvice(worthIt, hires, parallel);
    }

    private static Optional<Integer> parallel(String value) {
        Matcher n = NUMBER.matcher(value);
        return n.find() ? Optional.of(Math.clamp(Integer.parseInt(n.group()), 1, 4)) : Optional.empty();
    }

    private static Optional<StaffingReview.Hire> hire(String value) {
        String[] parts = value.split("\\|", 2);
        String name = parts[0].strip();
        if (name.isEmpty()) return Optional.empty();
        return Optional.of(new StaffingReview.Hire(name, parts.length > 1 ? parts[1].strip() : "Developer"));
    }
}
