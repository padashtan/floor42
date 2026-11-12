package dev.floor42.server.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the director installs a tool the sandbox lacks (ADR-0033). Floor42 never runs any of it: no sudo, no
 * {@code wsl -u root}. It writes the commands for the director to copy into their own WSL terminal. Recipes exist for
 * Debian and Ubuntu only (apt packages); Node newer than the distribution's comes from nodejs.org's official tarball,
 * unpacked into /usr/local (on the sandbox's PATH), never through nvm, whose home folder the sandbox cannot see. A tool
 * without a recipe is left to the director: nothing is invented.
 */
public final class InstallRecipes {

    private InstallRecipes() {}

    /**
     * What to do for one tool.
     *
     * @param packages apt packages; empty when the tool comes another way or has no recipe
     * @param script commands beyond apt, in order (the Node tarball); empty otherwise
     * @param manual true when Floor42 has no recipe: the director installs it as they see fit
     */
    public record Recipe(String tool, List<String> packages, List<String> script, boolean manual) {
        public Recipe {
            packages = packages == null ? null : Collections.unmodifiableList(new ArrayList<>(packages));
            script = script == null ? null : Collections.unmodifiableList(new ArrayList<>(script));
        }

        static Recipe manual(String tool) {
            return new Recipe(tool, List.of(), List.of(), true);
        }

        static Recipe apt(String tool, String... packages) {
            return new Recipe(tool, List.of(packages), List.of(), false);
        }
    }

    /** The distribution's own Node major, where Floor42 knows it; a wanted major above it takes the tarball. */
    static final Map<String, Integer> DISTRO_NODE = Map.of(
            "debian 12", 18,
            "debian 13", 20,
            "ubuntu 22.04", 12,
            "ubuntu 24.04", 18);

    /** Whether the distribution is one Floor42 has recipes for: Debian, Ubuntu, or one built on them. */
    public static boolean supported(String osId, String osLike) {
        String all = ((osId == null ? "" : osId) + " " + (osLike == null ? "" : osLike)).toLowerCase(Locale.ROOT);
        return all.matches(".*\\b(debian|ubuntu)\\b.*");
    }

    /** The recipe for a tool at a wanted version (null when none is pinned) on this distribution. */
    public static Recipe recipe(String tool, String wanted, String osId, String osLike, String osVersion) {
        if (!supported(osId, osLike)) return Recipe.manual(tool);
        Optional<Integer> major = major(wanted);
        return switch (tool) {
            case "git" -> Recipe.apt(tool, "git");
            case "java" ->
                Recipe.apt(
                        tool,
                        major.filter(m -> m >= 8)
                                .map(m -> "openjdk-" + m + "-jdk-headless")
                                .orElse("default-jdk-headless"));
            case "mvn" -> Recipe.apt(tool, "maven");
            case "make" -> Recipe.apt(tool, "make");
            case "curl" -> Recipe.apt(tool, "curl", "ca-certificates");
            case "python3" -> Recipe.apt(tool, "python3");
            case "pip3" -> Recipe.apt(tool, "python3-pip", "python3-venv");
            case "go" -> Recipe.apt(tool, "golang-go");
            case "cargo", "rustc" -> Recipe.apt(tool, "cargo", "rustc");
            case "node", "npm" -> node(tool, major, osId, osVersion);
            case "chromium", "pdftotext", "pandoc", "libreoffice" ->
                Recipe.apt(
                        tool,
                        SandboxToolkit.essential(tool).orElseThrow().packages().toArray(String[]::new));
            default -> Recipe.manual(tool);
        };
    }

    private static Recipe node(String tool, Optional<Integer> major, String osId, String osVersion) {
        Integer own = DISTRO_NODE.get((osId == null ? "" : osId.toLowerCase(Locale.ROOT)) + " " + osVersion);
        // No version pinned: the distribution's packages. A major above the distribution's, or one Floor42 cannot
        // compare with it: the official tarball of that major.
        if (major.isEmpty() || own != null && major.get() <= own) return Recipe.apt(tool, "nodejs", "npm");
        String dir = "https://nodejs.org/dist/latest-v" + major.get() + ".x";
        return new Recipe(
                tool,
                List.of("curl", "ca-certificates", "xz-utils"),
                List.of(
                        "ARCH=$(dpkg --print-architecture | sed 's/amd64/x64/')",
                        "cd /tmp && curl -fsSLO " + dir + "/SHASUMS256.txt",
                        "F=$(grep -o \"node-v[0-9.]*-linux-$ARCH.tar.xz\" SHASUMS256.txt | head -1)",
                        "curl -fsSLO " + dir + "/$F && grep \" $F\\$\" SHASUMS256.txt | sha256sum -c -",
                        "sudo tar -xJf \"$F\" -C /usr/local --strip-components=1 --exclude='*.md' --exclude=LICENSE",
                        "node --version"),
                false);
    }

    /**
     * The commands to copy, for the tools to install: one apt line for every package, then the scripts. Empty when
     * nothing has a recipe.
     */
    public static List<String> commands(List<Recipe> recipes) {
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        for (Recipe r : recipes) {
            packages.addAll(r.packages());
            scripts.addAll(r.script());
        }
        if (!packages.isEmpty()) {
            lines.add("sudo apt-get update && sudo apt-get install -y " + String.join(" ", packages));
        }
        lines.addAll(scripts);
        return lines;
    }

    /** The major (first number) of a wanted version such as "21", ">=18", "v20.11", "^22.1.0"; empty without one. */
    static Optional<Integer> major(String wanted) {
        if (wanted == null) return Optional.empty();
        Matcher m = Pattern.compile("(\\d+)").matcher(wanted);
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(m.group(1)));
        } catch (NumberFormatException e) {
            // Handled: a version that is no number reads as unknown
            return Optional.empty();
        }
    }
}
