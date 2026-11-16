package dev.floor42.server.app.mission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a project folder says of itself before a mission (ADR-0070): its build tool, the files that say so, and a
 * checks command to propose. Pure file reading beside {@link ProjectToolchain}: nothing runs, nothing is written, a
 * file it cannot read counts as absent. The proposed command is a table lookup, never run here.
 */
public final class ProjectProfile {

    /**
     * @param buildTool Maven, Gradle, Node, Python, Rust, Go or Make; null when no file names one
     * @param files the files of that tool found at the root, as named on disk
     * @param suggestedChecks the table's checks command for the first tool that has one; null when none
     * @param downloads the build files that usually download dependencies when they run (mvnw, gradlew, package.json
     *     without node_modules)
     */
    public record Profile(String buildTool, List<String> files, String suggestedChecks, List<String> downloads) {
        public Profile {
            files = List.copyOf(files);
            downloads = List.copyOf(downloads);
        }
    }

    private static final Pattern NPM_TEST = Pattern.compile("\"scripts\"\\s*:\\s*\\{[^}]*?\"test\"\\s*:");
    private static final Pattern MAKE_TEST = Pattern.compile("(?m)^test\\s*:");

    private ProjectProfile() {}

    public static Profile read(Path root) {
        String tool = null;
        List<String> toolFiles = List.of();
        String checks = null;
        List<String> downloads = new ArrayList<>();
        if (root != null && Files.isDirectory(root)) {
            for (Candidate c : candidates(root)) {
                if (c.files.isEmpty()) continue;
                if (tool == null) {
                    tool = c.tool;
                    toolFiles = c.files;
                }
                if (checks == null) checks = c.checks;
                downloads.addAll(c.downloads);
            }
        }
        return new Profile(tool, toolFiles, checks, downloads);
    }

    private record Candidate(String tool, List<String> files, String checks, List<String> downloads) {}

    private static List<Candidate> candidates(Path root) {
        List<Candidate> all = new ArrayList<>();
        all.add(maven(root));
        all.add(gradle(root));
        all.add(node(root));
        all.add(simple("Python", present(root, "pyproject.toml", "pytest.ini"), "pytest"));
        all.add(simple("Rust", present(root, "Cargo.toml"), "cargo test"));
        all.add(simple("Go", present(root, "go.mod"), "go test ./..."));
        all.add(make(root));
        return all;
    }

    private static Candidate maven(Path root) {
        List<String> files = present(root, "pom.xml", "mvnw");
        boolean wrapper = files.contains("mvnw");
        return new Candidate(
                "Maven",
                files,
                wrapper ? "./mvnw -q verify" : files.isEmpty() ? null : "mvn -q verify",
                wrapper ? List.of("mvnw") : List.of());
    }

    private static Candidate gradle(Path root) {
        List<String> files = present(root, "gradlew", "build.gradle", "build.gradle.kts");
        boolean wrapper = files.contains("gradlew");
        return new Candidate(
                "Gradle", files, wrapper ? "./gradlew check" : null, wrapper ? List.of("gradlew") : List.of());
    }

    private static Candidate node(Path root) {
        List<String> files = present(root, "package.json");
        boolean test = ProjectToolchain.text(root.resolve("package.json"))
                .map(s -> NPM_TEST.matcher(s).find())
                .orElse(false);
        boolean modules = Files.isDirectory(root.resolve("node_modules"));
        return new Candidate(
                "Node",
                files,
                test ? "npm test" : null,
                !files.isEmpty() && !modules ? List.of("package.json") : List.of());
    }

    private static Candidate make(Path root) {
        List<String> files = present(root, "Makefile", "makefile");
        boolean target = files.stream()
                .findFirst()
                .flatMap(f -> ProjectToolchain.text(root.resolve(f)))
                .map(s -> MAKE_TEST.matcher(s).find())
                .orElse(false);
        return new Candidate("Make", files, target ? "make test" : null, List.of());
    }

    /** A tool whose checks command applies as soon as one of its files is there. */
    private static Candidate simple(String tool, List<String> files, String checks) {
        return new Candidate(tool, files, files.isEmpty() ? null : checks, List.of());
    }

    /** The files among {@code names} that are regular files of at most the size read (a bigger one counts as absent). */
    private static List<String> present(Path root, String... names) {
        List<String> found = new ArrayList<>();
        for (String n : names) {
            Path f = root.resolve(n);
            try {
                if (Files.isRegularFile(f) && Files.size(f) <= ProjectToolchain.MAX_FILE) found.add(n);
            } catch (java.io.IOException | RuntimeException e) {
                // Handled: a file that cannot be read counts as absent
            }
        }
        return found;
    }
}
