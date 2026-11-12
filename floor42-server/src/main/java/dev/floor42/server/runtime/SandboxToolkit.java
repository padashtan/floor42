package dev.floor42.server.runtime;

import dev.floor42.domain.SandboxTools;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The tools an agent's work most often needs inside the shell sandbox, so Floor42 can say up front which ones the
 * sandbox lacks, instead of an agent looping on a step that cannot run there. Each essential tool may be found under
 * several command names (a browser is {@code chromium} on Debian, {@code chromium-browser} or {@code google-chrome}
 * elsewhere). Floor42 never installs any of them: it writes the command for the director to copy.
 */
public final class SandboxToolkit {

    private SandboxToolkit() {}

    /** What one tool's state is in the last measure. */
    public enum State {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    /**
     * One essential tool.
     *
     * @param name the name briefs, plans and screens use
     * @param commands the command names that provide it, any one is enough
     * @param packages the Debian and Ubuntu packages that install it
     */
    public record Essential(String name, List<String> commands, List<String> packages) {}

    /** In the order the screens and briefs list them. */
    public static final List<Essential> ESSENTIALS = List.of(
            new Essential("git", List.of("git"), List.of("git")),
            new Essential("python3", List.of("python3"), List.of("python3")),
            new Essential("node", List.of("node"), List.of("nodejs", "npm")),
            new Essential("java", List.of("java"), List.of("default-jdk-headless")),
            new Essential("make", List.of("make"), List.of("make")),
            new Essential(
                    "chromium",
                    List.of("chromium", "chromium-browser", "google-chrome", "google-chrome-stable"),
                    List.of("chromium")),
            new Essential("pdftotext", List.of("pdftotext"), List.of("poppler-utils")),
            new Essential("pandoc", List.of("pandoc"), List.of("pandoc")),
            new Essential("libreoffice", List.of("libreoffice", "soffice"), List.of("libreoffice-nogui")));

    /** Other words a planner may use for an essential tool. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("python", "python3"),
            Map.entry("nodejs", "node"),
            Map.entry("npm", "node"),
            Map.entry("jdk", "java"),
            Map.entry("chrome", "chromium"),
            Map.entry("chromium-browser", "chromium"),
            Map.entry("google-chrome", "chromium"),
            Map.entry("google-chrome-stable", "chromium"),
            Map.entry("browser", "chromium"),
            Map.entry("headless-chrome", "chromium"),
            Map.entry("poppler", "pdftotext"),
            Map.entry("poppler-utils", "pdftotext"),
            Map.entry("soffice", "libreoffice"));

    /** Every command name the probe asks about for the essentials. */
    public static List<String> commands() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Essential e : ESSENTIALS) all.addAll(e.commands());
        return List.copyOf(all);
    }

    /** The essential tool of this name, alias or command; empty for any other tool. */
    public static Optional<Essential> essential(String name) {
        String n = normalize(name);
        return ESSENTIALS.stream().filter(e -> e.name().equals(n)).findFirst();
    }

    /**
     * A tool name as a planner wrote it, made plain: lower case, no decoration, aliases folded into the essential's
     * name. Null for what is no command name (a sentence, "none").
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\(.*?\\)", "")
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?:^[`*_\"']+)|(?:[`*_\"'.]+$)", "")
                .replaceAll("^(a|an|the) ", "")
                .replaceAll("\\s+", "-");
        if (!s.matches("[a-z0-9][a-z0-9._+-]{0,39}") || s.matches("none|no|n/a|nothing")) return null;
        // Several words are a tool only when they name an essential one ("headless chrome"), never a sentence.
        boolean words = raw.replaceAll("\\(.*?\\)", "").strip().matches(".*\\s.*");
        if (words && !ALIASES.containsKey(s)) return null;
        return ALIASES.getOrDefault(s, s);
    }

    /** The commands that provide a tool: the essential's, or the name alone for any other tool. */
    public static List<String> commandsOf(String name) {
        return essential(name).map(Essential::commands).orElse(List.of(name));
    }

    /**
     * The tool's state in a measure: PRESENT when any of its commands was found, ABSENT when every one of them was
     * measured missing, UNKNOWN otherwise (not asked about, or nothing measured).
     */
    public static State state(SandboxTools last, String name) {
        if (last == null || !last.measured()) return State.UNKNOWN;
        boolean allMeasured = true;
        for (String command : commandsOf(name)) {
            Optional<SandboxTools.Tool> t = last.tool(command);
            if (t.isPresent() && t.get().present()) return State.PRESENT;
            if (t.isEmpty()) allMeasured = false;
        }
        return allMeasured ? State.ABSENT : State.UNKNOWN;
    }

    /** The version line of the first command found, or null. */
    public static String version(SandboxTools last, String name) {
        if (last == null || !last.measured()) return null;
        for (String command : commandsOf(name)) {
            Optional<SandboxTools.Tool> t = last.tool(command).filter(SandboxTools.Tool::present);
            if (t.isPresent()) return t.get().version();
        }
        return null;
    }

    /**
     * The one command the director copies into a Windows terminal to install these tools in the distribution, as its
     * root user; empty when Floor42 has no package for any of them (or the distribution is not Debian or Ubuntu, when
     * it is known). Floor42 never runs it.
     */
    public static Optional<String> installCommand(String distro, List<String> tools, String osId, String osLike) {
        boolean known = osId != null || osLike != null;
        if (known && !InstallRecipes.supported(osId, osLike)) return Optional.empty();
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        for (String t : tools) essential(t).ifPresent(e -> packages.addAll(e.packages()));
        if (packages.isEmpty()) return Optional.empty();
        String d = distro == null || !distro.matches("[A-Za-z0-9._-]{1,64}") ? "Debian" : distro;
        return Optional.of("wsl -d " + d + " -u root -- sh -c \"apt-get update && apt-get install -y "
                + String.join(" ", packages) + "\"");
    }

    /**
     * {@link #installCommand} for the machine Floor42 runs on: on Linux the sandbox is the machine's own system, so the
     * director copies a {@code sudo} command; on Windows the {@code wsl} one. Floor42 never runs it.
     */
    public static Optional<String> installCommandHere(String distro, List<String> tools, String osId, String osLike) {
        Optional<String> wsl = installCommand(distro, tools, osId, osLike);
        if (!SandboxHost.linux(SandboxHost.current())) return wsl;
        return wsl.map(c -> c.replaceFirst("^wsl -d \\S+ -u root -- sh -c ", "sudo sh -c "));
    }

    /** The tools, in the essentials' order first, without repeats. */
    public static List<String> ordered(java.util.Collection<String> tools) {
        List<String> out = new ArrayList<>();
        for (Essential e : ESSENTIALS) if (tools.contains(e.name())) out.add(e.name());
        for (String t : tools) if (!out.contains(t)) out.add(t);
        return out;
    }
}
