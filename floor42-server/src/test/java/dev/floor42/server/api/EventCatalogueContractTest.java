package dev.floor42.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.floor42.domain.ApprovalScope;
import dev.floor42.domain.DecisionBasis;
import dev.floor42.server.api.event.EnglishLines;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The two files that tie the server's event lines to the screen's catalogues, refreshed like the API snapshot (run the
 * build with {@code -Dfloor42.openapi.write=true}):
 *
 * <ul>
 *   <li>{@code floor42-web/src/i18n/event-codes.json}: every code the server can emit (the golden journals and
 *       {@link EventVariants}), the values it always provides, and one sample with the English line the server writes
 *       for it. The screen's {@code catalogue.test.ts} checks that en.ts and fr.ts both have each code, with only
 *       placeholders those values fill, and that its own {@code eventText} writes the sample's line.
 *   <li>{@code src/main/resources/event-lines.en.json}: the entries of en.ts {@link EnglishLines} writes lines from.
 * </ul>
 */
class EventCatalogueContractTest {

    static final Path CODES = Path.of("..", "floor42-web", "src", "i18n", "event-codes.json");
    static final Path BASES = Path.of("..", "floor42-web", "src", "i18n", "decision-bases.json");
    static final Path CONTEXTS = Path.of("..", "floor42-web", "src", "i18n", "approval-contexts.json");
    static final Path EN = Path.of("..", "floor42-web", "src", "i18n", "en.ts");
    static final Path LINES = Path.of("src", "main", "resources", "event-lines.en.json");

    /** The keys a line can use: the lines, their tails, the fixed server words, and the subjects of phrases. */
    private static final Pattern USED = Pattern.compile(
            "ev\\..+|verdict\\..+|criticality\\..+|role\\..+|risk\\.(?:level|reason|step)\\..+|q\\..+\\.subject");

    /** {@code 'key': 'value',} or with a double-quoted value, one entry per line as en.ts writes them. */
    private static final Pattern ENTRY =
            Pattern.compile("^\\s*'([^']+)':\\s*('(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")\\s*,?\\s*$");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test
    void theServerWritesLinesFromTheEnglishCatalogueAsItIs() throws Exception {
        String extracted = json(englishEntries());
        if (writing()) Files.writeString(LINES, extracted, StandardCharsets.UTF_8);
        assertThat(read(LINES))
                .as("en.ts changed: refresh %s (see this test's documentation)", LINES)
                .isEqualTo(extracted);
    }

    @Test
    void theScreenKnowsEveryCodeTheServerCanEmit() throws Exception {
        EnglishLines english = EnglishLines.from(englishEntries());
        Map<String, Map<String, Object>> codes = new TreeMap<>();
        Map<String, Set<String>> always = new TreeMap<>();
        EventViewCharacterisationTest.rendered().values().stream()
                .flatMap(List::stream)
                .forEach(v -> {
                    always.computeIfAbsent(
                                    v.code(), k -> new TreeSet<>(v.params().keySet()))
                            .retainAll(v.params().keySet());
                    codes.computeIfAbsent(v.code(), k -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("sample", v.params());
                        entry.put("line", english.line(v.code(), v.params()));
                        return entry;
                    });
                });
        codes.forEach((code, entry) -> entry.put("params", always.get(code)));
        String expected = json(codes);
        if (writing()) Files.writeString(CODES, expected, StandardCharsets.UTF_8);
        assertThat(read(CODES))
                .as("The server's event codes changed: refresh %s (see this test's documentation)", CODES)
                .isEqualTo(expected);
    }

    /**
     * {@code floor42-web/src/i18n/approval-contexts.json}: for each code of an approval context, one context as the
     * golden journals recorded it, with the code and values the question view gives for it. The screen's
     * {@code events.test.ts} writes each one back from its code, in English to the letter and in French without English.
     */
    @Test
    void theScreenGetsEveryApprovalContextAsACode() throws Exception {
        Map<String, Map<String, Object>> samples = new TreeMap<>();
        EventViewCharacterisationTest.rendered().values().stream()
                .flatMap(List::stream)
                .filter(v -> v.code().startsWith("QuestionAsked"))
                .map(v -> v.params().get("context"))
                .forEach(context -> ApprovalScope.read(context)
                        .ifPresent(p -> samples.computeIfAbsent(p.code(), k -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("context", context);
                            entry.put("params", new TreeMap<>(p.params()));
                            return entry;
                        })));
        assertThat(samples.keySet())
                .as("every approval context Floor42 writes is in a golden journal")
                .containsExactlyInAnyOrder(
                        ApprovalScope.CODE_COMMAND,
                        ApprovalScope.CODE_TOOL,
                        ApprovalScope.CODE_EDITED,
                        ApprovalScope.CODE_ASK_ALWAYS);
        String expected = json(samples);
        if (writing()) Files.writeString(CONTEXTS, expected, StandardCharsets.UTF_8);
        assertThat(read(CONTEXTS))
                .as("The approval contexts changed: refresh %s (see this test's documentation)", CONTEXTS)
                .isEqualTo(expected);
    }

    /**
     * {@code floor42-web/src/i18n/decision-bases.json}: for each code of a decision's basis (an authorization's basis, a
     * denial's detail), one text as the golden journals recorded it, with the values {@link DecisionBasis#read} gives;
     * under {@code uncoded}, every other basis text met, shown as recorded (a guard's finding, a tool's own error). The
     * screen's {@code events.test.ts} writes each coded one back, in English to the letter and in French without English.
     */
    @Test
    void theScreenGetsEveryDecisionBasisAsACode() throws Exception {
        Map<String, Map<String, Object>> samples = new TreeMap<>();
        Set<String> uncoded = new TreeSet<>();
        EventViewCharacterisationTest.rendered().values().stream()
                .flatMap(List::stream)
                .filter(v -> v.code().equals("ActionAuthorized") || v.code().equals("ActionDenied"))
                .forEach(v -> {
                    String field = v.code().equals("ActionAuthorized") ? "basis" : "detail";
                    String text = v.params().get(field);
                    if (text == null || text.isEmpty()) return;
                    DecisionBasis.read(text)
                            .ifPresentOrElse(
                                    p -> samples.computeIfAbsent(p.code(), k -> {
                                        Map<String, Object> entry = new LinkedHashMap<>();
                                        entry.put("text", text);
                                        entry.put("params", new TreeMap<>(p.params()));
                                        assertThat(v.params())
                                                .as("the event view carries the code of " + text)
                                                .containsEntry(field + "Code", p.code());
                                        return entry;
                                    }),
                                    () -> uncoded.add(text));
                });
        assertThat(samples.keySet())
                .as("the bases the golden journals record")
                .contains(
                        DecisionBasis.CODE_ALLOWED,
                        DecisionBasis.CODE_DENIED,
                        DecisionBasis.CODE_ALREADY_ALLOWED_COMMAND,
                        DecisionBasis.CODE_DEFAULT_DENY);
        // The other fields that may hold a sentence Floor42 wrote: a rule, a reason, an incident's description.
        Map<String, Map<String, Map<String, Object>>> fields = new TreeMap<>();
        EventViewCharacterisationTest.rendered().values().stream()
                .flatMap(List::stream)
                .forEach(v -> {
                    for (String field : List.of("rule", "reason", "description")) {
                        String code = v.params().get(field + "Code");
                        if (code == null) continue;
                        fields.computeIfAbsent(field, f -> new TreeMap<>()).computeIfAbsent(code, k -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("text", v.params().get(field));
                            Map<String, String> own = new TreeMap<>();
                            v.params().forEach((name, value) -> {
                                if (name.startsWith(field + ".")) own.put(name.substring(field.length() + 1), value);
                            });
                            entry.put("params", own);
                            return entry;
                        });
                    }
                });
        assertThat(fields.keySet())
                .as("rules, reasons and descriptions Floor42 wrote")
                .contains("rule", "reason");
        Map<String, Object> all = new LinkedHashMap<>(samples);
        all.put("uncoded", uncoded);
        all.put("fields", fields);
        String expected = json(all);
        if (writing()) Files.writeString(BASES, expected, StandardCharsets.UTF_8);
        assertThat(read(BASES))
                .as("The decision bases changed: refresh %s (see this test's documentation)", BASES)
                .isEqualTo(expected);
    }

    /** The entries of en.ts the server's lines use, as the screen reads them. */
    static Map<String, String> englishEntries() throws Exception {
        Map<String, String> entries = new TreeMap<>();
        List<Path> files;
        try (var stream = Files.list(EN.getParent())) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".en.ts"))
                    .sorted()
                    .toList();
        }
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher m = ENTRY.matcher(line);
                if (m.matches() && USED.matcher(m.group(1)).matches()) entries.put(m.group(1), unquote(m.group(2)));
            }
        }
        assertThat(entries).as("lines read from " + EN).containsKey("ev.AgentHired");
        return entries;
    }

    private static String unquote(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch != '\\') {
                out.append(ch);
                continue;
            }
            char next = body.charAt(++i);
            out.append(next == 'n' ? '\n' : next);
        }
        return out.toString();
    }

    /** Pretty JSON with the same line ends on every machine. */
    private static String json(Object value) throws Exception {
        return JSON.writeValueAsString(value).replace("\r\n", "\n") + "\n";
    }

    private static String read(Path file) throws Exception {
        return Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
                : "";
    }

    private static boolean writing() {
        return Boolean.getBoolean("floor42.openapi.write");
    }
}
