package dev.floor42.server.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tools a project needs, read from the files at its root (ADR-0033): build files, version pins, and the first word
 * of the checks command. Pure file reading: nothing runs, nothing is written, nothing is guessed beyond what a file
 * says. A file it cannot read counts as absent.
 */
public final class ProjectToolchain {

    /** The largest file read: build files are small; a bigger one is not a build file worth parsing. */
    static final long MAX_FILE = 512 * 1024;

    /**
     * One tool the project needs.
     *
     * @param wanted the version a file asks for, as written (such as "21", ">=18", "3.11"); null when none is pinned
     * @param because the files (or "check command") that say so
     */
    public record Need(String tool, String wanted, List<String> because) {
        public Need {
            because = because == null ? null : Collections.unmodifiableList(new ArrayList<>(because));
        }
    }

    /** Shell words that are not a tool of their own. */
    private static final Set<String> SHELLS = Set.of("sh", "bash", "env", "cd", "exec", "time", "nice", "sudo");

    private ProjectToolchain() {}

    /** What the project at {@code root} needs, in a stable order; {@code checkCommand} may be null. */
    public static List<Need> read(Path root, String checkCommand) {
        Map<String, Builder> needs = new LinkedHashMap<>();
        if (root != null && Files.isDirectory(root)) {
            // Agents read the history: git is needed in any project.
            add(needs, "git", null, "git repository");
            jvm(root, needs);
            node(root, needs);
            python(root, needs);
            others(root, needs);
        }
        checkTool(checkCommand).ifPresent(tool -> add(needs, tool, null, "check command"));
        List<Need> result = new ArrayList<>();
        needs.forEach((tool, b) -> result.add(new Need(tool, b.wanted, List.copyOf(b.because))));
        return result;
    }

    private static void jvm(Path root, Map<String, Builder> needs) {
        Optional<String> pom = text(root.resolve("pom.xml"));
        boolean mvnw = Files.isRegularFile(root.resolve("mvnw"));
        if (pom.isPresent() || mvnw) {
            String release = pom.flatMap(ProjectToolchain::javaRelease).orElse(null);
            add(needs, "java", release, pom.isPresent() ? "pom.xml" : "mvnw");
            // The wrapper brings its own Maven (downloaded, so it needs the network).
            if (mvnw) add(needs, "java", null, "mvnw");
            else add(needs, "mvn", null, "pom.xml");
        }
        boolean gradlew = Files.isRegularFile(root.resolve("gradlew"));
        boolean gradle = Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"));
        if (gradlew || gradle) {
            add(needs, "java", null, gradlew ? "gradlew" : "build.gradle");
            if (!gradlew) add(needs, "gradle", null, "build.gradle");
        }
    }

    private static void node(Path root, Map<String, Builder> needs) {
        Optional<String> pkg = text(root.resolve("package.json"));
        if (pkg.isPresent()) {
            add(needs, "node", pkg.flatMap(ProjectToolchain::nodeEngine).orElse(null), "package.json");
            add(needs, "npm", null, "package.json");
        }
        text(root.resolve(".nvmrc"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .ifPresent(v -> add(needs, "node", v.startsWith("v") ? v.substring(1) : v, ".nvmrc"));
    }

    private static void python(Path root, Map<String, Builder> needs) {
        Optional<String> pyproject = text(root.resolve("pyproject.toml"));
        if (pyproject.isPresent()) {
            add(
                    needs,
                    "python3",
                    pyproject.flatMap(ProjectToolchain::requiresPython).orElse(null),
                    "pyproject.toml");
        }
        if (Files.isRegularFile(root.resolve("requirements.txt"))) {
            add(needs, "python3", null, "requirements.txt");
            add(needs, "pip3", null, "requirements.txt");
        }
    }

    private static void others(Path root, Map<String, Builder> needs) {
        text(root.resolve("go.mod"))
                .ifPresent(mod -> add(
                        needs,
                        "go",
                        first(Pattern.compile("(?m)^go\\s+(\\S+)"), mod).orElse(null),
                        "go.mod"));
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            String pin = rustToolchain(root).orElse(null);
            add(needs, "cargo", pin, "Cargo.toml");
            add(needs, "rustc", pin, "Cargo.toml");
        }
        if (Files.isRegularFile(root.resolve("Makefile")) || Files.isRegularFile(root.resolve("makefile"))) {
            add(needs, "make", null, "Makefile");
        }
        if (Files.isRegularFile(root.resolve("Dockerfile"))) add(needs, "docker", null, "Dockerfile");
    }

    /**
     * The tool the checks command starts with: its first word, without a path; a wrapper script stands for the tool it
     * needs ({@code ./mvnw} needs java). Empty for a shell word or nothing usable.
     */
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop") // the first word that is no assignment decides
    static Optional<String> checkTool(String command) {
        if (command == null) return Optional.empty();
        for (String word : command.strip().split("\\s+")) {
            if (word.isEmpty() || word.contains("=")) continue; // an environment assignment before the command
            String name = word.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".exe")) {
                name = name.substring(0, name.lastIndexOf('.'));
            }
            if (SHELLS.contains(name)) return Optional.empty();
            if (name.equals("mvnw") || name.equals("gradlew")) return Optional.of("java");
            if (name.equals("python")) return Optional.of("python3");
            if (name.equals("pip")) return Optional.of("pip3");
            if (name.equals("npx")) return Optional.of("npm");
            return name.matches("[a-z0-9][a-z0-9._+-]{0,39}") ? Optional.of(name) : Optional.empty();
        }
        return Optional.empty();
    }

    /** {@code maven.compiler.release}, else a {@code <release>} of the compiler plugin. */
    static Optional<String> javaRelease(String pom) {
        return first(Pattern.compile("<maven\\.compiler\\.release>\\s*([^<\\s]+)\\s*</"), pom)
                .or(() -> first(Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>"), pom))
                .filter(v -> !v.startsWith("$"));
    }

    /** {@code engines.node} of a package.json, read as text (a full JSON parser is not needed for one field). */
    static Optional<String> nodeEngine(String packageJson) {
        return first(Pattern.compile("\"engines\"\\s*:\\s*\\{[^}]*?\"node\"\\s*:\\s*\"([^\"]+)\""), packageJson);
    }

    static Optional<String> requiresPython(String pyproject) {
        return first(Pattern.compile("(?m)^\\s*requires-python\\s*=\\s*[\"']([^\"']+)[\"']"), pyproject);
    }

    /** A rust-toolchain file's channel: the plain file's text, or {@code channel} of rust-toolchain.toml. */
    static Optional<String> rustToolchain(Path root) {
        Optional<String> toml = text(root.resolve("rust-toolchain.toml"));
        if (toml.isPresent()) return first(Pattern.compile("(?m)^\\s*channel\\s*=\\s*\"([^\"]+)\""), toml.get());
        Optional<String> plain = text(root.resolve("rust-toolchain"));
        if (plain.isEmpty()) return Optional.empty();
        Optional<String> channel = first(Pattern.compile("(?m)^\\s*channel\\s*=\\s*\"([^\"]+)\""), plain.get());
        if (channel.isPresent()) return channel;
        String s = plain.get().strip();
        return s.isEmpty() || s.contains("\n") ? Optional.empty() : Optional.of(s);
    }

    private static Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }

    private static Optional<String> text(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE) return Optional.empty();
            return Optional.of(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            // Handled: a file that cannot be read gives no toolchain hint, never a guess
            return Optional.empty();
        }
    }

    private static final class Builder {
        String wanted;
        final List<String> because = new ArrayList<>();
    }

    private static void add(Map<String, Builder> needs, String tool, String wanted, String because) {
        Builder b = needs.computeIfAbsent(tool, k -> new Builder());
        if (b.wanted == null && wanted != null && !wanted.isBlank()) b.wanted = wanted.strip();
        if (!b.because.contains(because)) b.because.add(because);
    }
}
