package dev.floor42.server.api.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An event's line in English, written on the server from the screen's own English catalogue, for what leaves Floor42
 * without a screen to translate it (a mission's replay file). {@code event-lines.en.json} is the part of
 * {@code floor42-web/src/i18n/en.ts} lines need, extracted by {@code EventCatalogueContractTest}; the filling follows
 * {@code eventText} in {@code floor42-web/src/i18n/events.ts}, and the screen's tests check both write the same line.
 */
public final class EnglishLines {

    static final String RESOURCE = "/event-lines.en.json";

    /** Read on first use. */
    private static final class Served {
        static final EnglishLines LINES = new EnglishLines(load());
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /** Event fields holding a fixed server word, shown with the catalogue's word for it. */
    private static final Map<String, String> WORDS = Map.of(
            "verdict",
            "verdict",
            "declared",
            "verdict",
            "criticality",
            "criticality",
            "workRole",
            "role",
            "audience",
            "role");

    private record Tail(String key, Predicate<Map<String, String>> shown) {}

    /** Optional tails some lines carry, each shown only when its field says so. */
    private static final Map<String, List<Tail>> TAILS = Map.of(
            "CommitteeContributed",
            List.of(
                    new Tail(
                            "ev.more.proposal",
                            p -> !p.getOrDefault("proposal", "").isEmpty()),
                    new Tail(
                            "ev.more.objection",
                            p -> !p.getOrDefault("criticalObjection", "").isEmpty())),
            "CommitteeArbitrated",
            List.of(
                    new Tail("ev.more.despite", p -> "true".equals(p.get("overCriticalObjection"))),
                    new Tail("ev.more.implement", p -> "true".equals(p.get("implement")))),
            "BranchesMerged",
            List.of(new Tail(
                    "ev.more.detail", p -> !p.getOrDefault("detail", "").isBlank())));

    private final Map<String, String> catalogue;

    private EnglishLines(Map<String, String> catalogue) {
        this.catalogue = Map.copyOf(catalogue);
    }

    /** Lines written from the given catalogue entries (a catalogue just read again from {@code en.ts}). */
    public static EnglishLines from(Map<String, String> catalogue) {
        return new EnglishLines(catalogue);
    }

    /** The line of a code, from the catalogue the daemon was built with. */
    public static String of(String code, Map<String, String> params) {
        return Served.LINES.line(code, params);
    }

    /** The line of a code, filled with its values; the code itself when the catalogue does not know it. */
    public String line(String code, Map<String, String> params) {
        String template = catalogue.get("ev." + code);
        if (template == null) return code;
        Map<String, String> values = new HashMap<>(params);
        WORDS.forEach((field, family) -> {
            String v = values.get(field);
            if (v != null && !v.isEmpty())
                values.put(field, catalogue.getOrDefault(family + "." + v, v.toLowerCase(Locale.ROOT)));
        });
        if (code.equals("SessionRiskRaised") || code.equals("MissionQuarantined")) {
            String level = values.getOrDefault("level", "");
            values.put("level", catalogue.getOrDefault("risk.level." + level, level));
            values.put("reasons", words("risk.reason", values.get("reasons")));
            if (code.equals("MissionQuarantined")) values.put("done", words("risk.step", values.get("done")));
        }
        String phrase = params.get("phrase");
        if (phrase != null && !phrase.isEmpty()) values.put("subject", subject(phrase, params, values));
        StringBuilder line = new StringBuilder(fill(template, values));
        for (Tail tail : TAILS.getOrDefault(code.split("\\.")[0], List.of())) {
            if (tail.shown().test(params)) line.append(fill(catalogue.get(tail.key()), values));
        }
        return line.toString();
    }

    /** A comma-separated list of codes (risk reasons, quarantine steps) in the catalogue's words, as the screen does. */
    private String words(String family, String codes) {
        if (codes == null) return "";
        List<String> out = new java.util.ArrayList<>();
        for (String c : codes.split(",\\s*")) if (!c.isEmpty()) out.add(catalogue.getOrDefault(family + "." + c, c));
        return String.join(", ", out);
    }

    /** The subject Floor42 wrote for a question, from its phrase when the catalogue has it, else as recorded. */
    private String subject(String phrase, Map<String, String> params, Map<String, String> values) {
        String template = catalogue.get("q." + phrase + ".subject");
        String recorded = values.getOrDefault("subject", "");
        if (template == null) return recorded;
        Map<String, String> own = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith("phrase.")) own.put(k.substring(7), v);
        });
        return fill(template, own);
    }

    /** {@code {name}} placeholders filled; one without a value stays as it is. */
    static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Map<String, String> load() {
        try (InputStream in = EnglishLines.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing " + RESOURCE);
            // Not Quarkus's mapper: a classpath catalogue read once, in a static initialiser the renderers and their
            // tests use outside CDI; a plain map of strings, which no configuration changes.
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
