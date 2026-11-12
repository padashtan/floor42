import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A stand-in for Claude Code's plugin commands in tests (ADR-0040). It never touches the network nor a model: a
 * marketplace is a local folder ({@code .claude-plugin/marketplace.json} and {@code plugins/<name>/}), and installing
 * copies a plugin's folder into the configuration folder, as Claude Code lays it out ({@code plugins/cache/...},
 * {@code plugins/installed_plugins.json} version 2, {@code plugins/known_marketplaces.json}).
 *
 * <p>Usage: {@code java FakeClaudeCli.java --fake-state <dir> <claude arguments>}. The configuration folder is {@code
 * CLAUDE_CONFIG_DIR}, else the scenario's {@code home}. The state folder's {@code scenario} (properties): {@code
 * add=ok|fail}, {@code install=ok|fail|failAfter|hang}, {@code uninstall=ok|fail|failAfter}, {@code version=1.0.0}.
 * Every call is appended to {@code calls.log} with the configuration folder it acted on.
 */
public class FakeClaudeCli {

    public static void main(String[] argv) throws Exception {
        Path state = Path.of(argv[1]);
        List<String> args = List.of(argv).subList(2, argv.length);
        Properties scenario = new Properties();
        Path file = state.resolve("scenario");
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file)) {
                scenario.load(in);
            }
        }
        String env = System.getenv("CLAUDE_CONFIG_DIR");
        Path config = Path.of(env != null ? env : scenario.getProperty("home", state.resolve("home").toString()));
        Files.writeString(
                state.resolve("calls.log"),
                "[" + (env != null ? env : "default") + "] " + String.join(" ", args) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        if (args.equals(List.of("--version"))) {
            System.out.println("2.1.316 (Claude Code)");
            return;
        }
        Path plugins = config.resolve("plugins");
        Files.createDirectories(plugins);
        String command = String.join(" ", args.subList(0, Math.min(3, args.size())));
        if (command.equals("plugin marketplace add")) {
            if (scenario.getProperty("add", "ok").equals("fail")) fail("Failed to add marketplace");
            Path source = Path.of(args.get(3)).toAbsolutePath();
            String json = Files.readString(source.resolve(".claude-plugin").resolve("marketplace.json"));
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            if (!m.find()) fail("No marketplace name");
            Map<String, String> known = read(plugins.resolve("known.tsv"));
            known.put(m.group(1), source.toString());
            write(plugins.resolve("known.tsv"), known);
            writeKnown(plugins, known);
            System.out.println("{\"ok\":true,\"marketplace\":\"" + m.group(1) + "\"}");
            return;
        }
        String verb = args.size() >= 2 ? args.get(0) + " " + args.get(1) : "";
        if (verb.equals("plugin install")) {
            String mode = scenario.getProperty("install", "ok");
            if (mode.equals("hang")) Thread.sleep(60_000);
            if (mode.equals("fail")) fail("Failed to install: network unreachable");
            String[] id = args.get(2).split("@", 2);
            Map<String, String> known = read(plugins.resolve("known.tsv"));
            String market = known.get(id[1]);
            if (market == null) fail("Marketplace " + id[1] + " not found");
            String version = scenario.getProperty("version", "1.0.0");
            Path to = plugins.resolve("cache").resolve(id[1]).resolve(id[0]).resolve(version);
            copy(Path.of(market).resolve("plugins").resolve(id[0]), to);
            Map<String, String> installed = read(plugins.resolve("installed.tsv"));
            installed.put(args.get(2), to + "\t" + version);
            write(plugins.resolve("installed.tsv"), installed);
            writeInstalled(plugins, installed);
            if (mode.equals("failAfter")) fail("Installed, then failed to write the settings");
            System.out.println("{\"ok\":true,\"plugin\":\"" + args.get(2) + "\"}");
            return;
        }
        if (verb.equals("plugin uninstall")) {
            String mode = scenario.getProperty("uninstall", "ok");
            if (mode.equals("fail")) fail("Failed to uninstall");
            Map<String, String> installed = read(plugins.resolve("installed.tsv"));
            String row = installed.remove(args.get(2));
            if (row == null) fail("Plugin " + args.get(2) + " is not installed");
            delete(Path.of(row.split("\t")[0]));
            write(plugins.resolve("installed.tsv"), installed);
            writeInstalled(plugins, installed);
            if (mode.equals("failAfter")) fail("Uninstalled, then failed to write the settings");
            System.out.println("{\"ok\":true}");
            return;
        }
        fail("FakeClaudeCli does not know: " + String.join(" ", args));
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }

    static Map<String, String> read(Path tsv) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(tsv)) return out;
        for (String line : Files.readAllLines(tsv)) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return out;
    }

    static void write(Path tsv, Map<String, String> rows) throws IOException {
        StringBuilder s = new StringBuilder();
        rows.forEach((k, v) -> s.append(k).append('\t').append(v).append('\n'));
        Files.writeString(tsv, s.toString());
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static void writeKnown(Path plugins, Map<String, String> known) throws IOException {
        List<String> parts = new ArrayList<>();
        known.forEach((name, path) -> parts.add(q(name) + ":{\"source\":{\"source\":\"directory\",\"path\":" + q(path)
                + "},\"installLocation\":" + q(path) + ",\"lastUpdated\":\"2026-10-31T00:00:00Z\"}"));
        Files.writeString(plugins.resolve("known_marketplaces.json"), "{" + String.join(",", parts) + "}");
    }

    static void writeInstalled(Path plugins, Map<String, String> installed) throws IOException {
        List<String> parts = new ArrayList<>();
        installed.forEach((id, row) -> {
            String[] r = row.split("\t");
            parts.add(q(id) + ":[{\"scope\":\"user\",\"installPath\":" + q(r[0]) + ",\"version\":" + q(r[1])
                    + ",\"installedAt\":\"2026-10-31T00:00:00Z\"}]");
        });
        Files.writeString(
                plugins.resolve("installed_plugins.json"),
                "{\"version\":2,\"plugins\":{" + String.join(",", parts) + "}}");
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
