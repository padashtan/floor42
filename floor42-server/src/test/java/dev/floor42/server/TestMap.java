package dev.floor42.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The test map (tools/test-map.conf, ADR-0068): from changed paths to the tests they call for. JDK only, so the quick
 * loop and the gate run it from its source, with no build: {@code java
 * floor42-server/src/test/java/dev/floor42/server/TestMap.java select} reads paths on its input and prints three lines
 * ({@code java=}, {@code web=}, {@code e2e=}); {@code escapes <failing class>...} prints the survey line of the gate.
 * {@link TestMapGateTest} keeps the map honest.
 */
public final class TestMap {

    /** The map, from the repository's root. */
    public static final String FILE = "tools/test-map.conf";

    private static final Pattern CAPTURE = Pattern.compile("\\{([a-z]+)}");

    /** One line of the map: the paths it takes and the targets it gives them, placeholders not yet filled in. */
    public record Rule(String glob, Pattern pattern, List<String> targets) {}

    /** What a set of changed paths calls for. */
    public static final class Selection {
        public boolean javaAll;
        public boolean webAll;
        public boolean webRelated;
        public boolean e2eAll;
        /** Surefire patterns, the core set not included. */
        public final SortedSet<String> java = new TreeSet<>();
        /** Playwright grep terms: a domain tag or a journey's file name. */
        public final SortedSet<String> e2e = new TreeSet<>();
        /** Paths no rule matched: they call for everything. */
        public final List<String> unmapped = new ArrayList<>();
        /** Targets the map could not resolve (an unknown word, a context with no journeys line). */
        public final List<String> problems = new ArrayList<>();

        private final List<String> core;

        Selection(List<String> core) {
            this.core = core;
        }

        /** The Surefire patterns to run, the core set with them; empty when no Java test is called for. */
        public SortedSet<String> javaPatterns() {
            SortedSet<String> all = new TreeSet<>(java);
            if (!java.isEmpty()) all.addAll(core);
            return all;
        }

        /** Whether the selection runs this test class, given as a path ({@code dev/floor42/server/FooTest}). */
        public boolean runs(String testClass) {
            return javaAll || javaPatterns().stream().anyMatch(p -> selects(p, testClass));
        }

        String javaLine() {
            return javaAll ? "all" : String.join(",", javaPatterns());
        }

        String webLine() {
            return webAll ? "all" : webRelated ? "related" : "";
        }

        String e2eLine() {
            return e2eAll ? "all" : String.join("|", e2e);
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final List<String> core = new ArrayList<>();
    private final Map<String, List<String>> journeys = new LinkedHashMap<>();

    private TestMap() {}

    public static TestMap load(Path file) {
        TestMap map = new TestMap();
        try {
            for (String line : logicalLines(Files.readAllLines(file))) map.read(line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }

    /** The lines without comments and blanks, a continuation (leading space) joined to the line before it. */
    private static List<String> logicalLines(List<String> raw) {
        List<String> lines = new ArrayList<>();
        for (String l : raw) {
            if (l.isBlank() || l.startsWith("#")) continue;
            if (Character.isWhitespace(l.charAt(0)) && !lines.isEmpty())
                lines.set(lines.size() - 1, lines.getLast() + " " + l.strip());
            else lines.add(l.strip());
        }
        return lines;
    }

    private void read(String line) {
        int eq = line.indexOf(" = ");
        if (eq < 0) throw new IllegalArgumentException(FILE + ": no \" = \" in: " + line);
        String left = line.substring(0, eq).strip();
        List<String> targets = Arrays.asList(line.substring(eq + 3).strip().split("\\s+"));
        if (left.equals("core")) targets.forEach(t -> core.add(t.substring("java:".length())));
        else if (left.startsWith("e2e-of ")) journeys.put(left.substring(7).strip(), targets);
        else rules.add(new Rule(left, glob(left), targets));
    }

    /** A glob as a pattern: ** crosses folders, * stays in one, {name} captures one name. */
    static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (glob.startsWith("**/", i)) {
                re.append("(?:.*/)?");
                i += 2;
            } else if (glob.startsWith("**", i)) {
                re.append(".*");
                i++;
            } else if (c == '*') re.append("[^/]*");
            else if (c == '{') {
                int end = glob.indexOf('}', i);
                re.append("(?<").append(glob, i + 1, end).append(">[A-Za-z0-9_-]+)");
                i = end;
            } else re.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(re.toString());
    }

    /** Whether a Surefire pattern takes a test class given as a path; a pattern with no folder takes any package. */
    public static boolean selects(String pattern, String testClass) {
        return glob(pattern.contains("/") ? pattern : "**/" + pattern)
                .matcher(testClass)
                .matches();
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<String> core() {
        return core;
    }

    public Map<String, List<String>> journeys() {
        return journeys;
    }

    /** The first rule that takes the path, if any. */
    public Optional<Rule> ruleFor(String path) {
        return rules.stream().filter(r -> r.pattern().matcher(path).matches()).findFirst();
    }

    public Selection select(Collection<String> paths) {
        Selection s = new Selection(core);
        for (String raw : paths) {
            String path = raw.strip().replace('\\', '/');
            if (path.isEmpty()) continue;
            Optional<Rule> rule = ruleFor(path);
            if (rule.isEmpty()) {
                s.unmapped.add(path);
                s.javaAll = s.webAll = s.e2eAll = true;
                continue;
            }
            Matcher m = rule.get().pattern().matcher(path);
            m.matches();
            for (String target : rule.get().targets())
                add(s, CAPTURE.matcher(target).replaceAll(c -> Matcher.quoteReplacement(m.group(c.group(1)))));
        }
        return s;
    }

    private void add(Selection s, String target) {
        int colon = target.indexOf(':');
        String kind = colon < 0 ? target : target.substring(0, colon);
        String value = colon < 0 ? "" : target.substring(colon + 1);
        switch (kind) {
            case "none" -> {}
            case "java" -> {
                if (value.equals("all")) s.javaAll = true;
                else s.java.add(value);
            }
            case "web" -> {
                if (value.equals("all")) s.webAll = true;
                else s.webRelated = true;
            }
            case "e2e" -> {
                if (value.equals("all")) s.e2eAll = true;
                else s.e2e.add(value);
            }
            case "e2e-of" -> {
                List<String> terms = journeys.get(value);
                if (terms == null) {
                    // A context with no journeys line: every journey, and the gate says the line is missing.
                    s.problems.add("no \"e2e-of " + value + "\" line");
                    s.e2eAll = true;
                } else s.e2e.addAll(terms);
            }
            default -> s.problems.add("unknown target " + target);
        }
    }

    /** The gate's survey line: the failing classes (dotted names) the quick loop would not have run for these paths. */
    public String survey(Collection<String> paths, List<String> failing) {
        Selection s = select(paths);
        List<String> escaped = failing.stream()
                .filter(c -> !s.runs(c.replace('.', '/')))
                .sorted()
                .toList();
        String java = s.javaAll ? "all" : s.java.isEmpty() ? "none" : "targeted";
        return "survey: " + paths.size() + " paths, java " + java + ", " + failing.size() + " failing, "
                + escaped.size() + " escaped" + (escaped.isEmpty() ? "" : ": " + String.join(" ", escaped));
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !List.of("select", "escapes").contains(args[0])) {
            System.err.println("usage: TestMap select | escapes <failing class>...   (changed paths on the input)");
            System.exit(2);
        }
        List<String> paths = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                .lines()
                .filter(l -> !l.isBlank())
                .toList();
        TestMap map = load(Path.of(FILE));
        if (args[0].equals("escapes")) {
            System.out.println(map.survey(paths, List.of(args).subList(1, args.length)));
            return;
        }
        Selection s = map.select(paths);
        s.unmapped.forEach(p -> System.err.println("quick: no rule in " + FILE + " for " + p + ": everything runs."));
        s.problems.forEach(p -> System.err.println("quick: " + FILE + ": " + p));
        System.out.println("java=" + s.javaLine());
        System.out.println("web=" + s.webLine());
        System.out.println("e2e=" + s.e2eLine());
    }
}
