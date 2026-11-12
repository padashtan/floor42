import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A stand-in for GitHub's command line in tests (ADR-0036): it answers the few gh commands Floor42 uses, from a
 * scenario file, and keeps the pull requests it "opened" in a state folder. It never touches the network.
 *
 * <p>Usage: {@code java FakeGh.java --fake-state <dir> <gh arguments>}. The folder holds {@code scenario}
 * (properties: {@code auth=yes|no}, {@code default=main}, {@code create=ok|timeout|fail}), optionally
 * {@code checks.json}; the fake writes {@code prs.tsv}, {@code body.md} and appends every call to {@code calls.log}.
 */
public class FakeGh {

    public static void main(String[] argv) throws Exception {
        if (argv.length < 2 || !argv[0].equals("--fake-state")) {
            System.err.println("usage: FakeGh --fake-state <dir> <args>");
            System.exit(2);
        }
        Path state = Path.of(argv[1]);
        List<String> args = List.of(argv).subList(2, argv.length);
        Files.writeString(
                state.resolve("calls.log"),
                String.join(" ", args) + "\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        Properties scenario = new Properties();
        Path file = state.resolve("scenario");
        if (Files.exists(file)) {
            try (var in = Files.newBufferedReader(file)) {
                scenario.load(in);
            }
        }
        String command = args.size() >= 2 ? args.get(0) + " " + args.get(1) : String.join(" ", args);
        switch (command) {
            case "auth status" -> {
                if (scenario.getProperty("auth", "yes").equals("yes")) {
                    System.err.println("github.com\n  Logged in to github.com account director (keyring)");
                } else {
                    System.err.println("You are not logged into any GitHub hosts. To log in, run: gh auth login");
                    System.exit(1);
                }
            }
            case "repo view" -> System.out.println("{\"defaultBranchRef\":{\"name\":\""
                    + scenario.getProperty("default", "main") + "\"},\"url\":\"https://github.com/acme/app\"}");
            case "pr list" -> {
                String head = option(args, "--head");
                StringBuilder out = new StringBuilder("[");
                for (String[] pr : prs(state)) {
                    if (!pr[2].equals(head)) continue;
                    if (out.length() > 1) out.append(',');
                    out.append("{\"number\":")
                            .append(pr[0])
                            .append(",\"url\":\"")
                            .append(pr[1])
                            .append("\",\"headRefName\":\"")
                            .append(pr[2])
                            .append("\",\"baseRefName\":\"")
                            .append(pr[3])
                            .append("\"}");
                }
                System.out.println(out.append(']'));
            }
            case "pr create" -> {
                String mode = scenario.getProperty("create", "ok");
                if (mode.equals("fail")) {
                    System.err.println("pull request create failed: HTTP 502: Bad Gateway");
                    System.exit(1);
                }
                Files.copy(
                        Path.of(option(args, "--body-file")),
                        state.resolve("body.md"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(state.resolve("title.txt"), option(args, "--title"), StandardCharsets.UTF_8);
                int number = 7 + prs(state).size();
                String url = "https://github.com/acme/app/pull/" + number;
                Files.writeString(
                        state.resolve("prs.tsv"),
                        number + "\t" + url + "\t" + option(args, "--head") + "\t" + option(args, "--base") + "\n",
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                if (mode.equals("timeout")) {
                    // GitHub created it, but the answer never comes back.
                    Thread.sleep(60_000);
                }
                System.out.println("Creating pull request for " + option(args, "--head") + " into "
                        + option(args, "--base") + "\n\n" + url);
            }
            case "pr checks" -> {
                Path checks = state.resolve("checks.json");
                if (!Files.exists(checks)) {
                    System.err.println("no checks reported on the '" + args.get(2) + "' branch");
                    System.exit(1);
                }
                String json = Files.readString(checks, StandardCharsets.UTF_8);
                System.out.println(json);
                if (json.contains("\"fail\"")) System.exit(1);
                if (json.contains("\"pending\"")) System.exit(8);
            }
            default -> {
                System.err.println("unknown command: " + String.join(" ", args));
                System.exit(1);
            }
        }
    }

    static String option(List<String> args, String name) {
        int i = args.indexOf(name);
        return i < 0 || i + 1 >= args.size() ? "" : args.get(i + 1);
    }

    static List<String[]> prs(Path state) throws Exception {
        Path file = state.resolve("prs.tsv");
        List<String[]> all = new ArrayList<>();
        if (!Files.exists(file)) return all;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) all.add(line.split("\t", 4));
        }
        return all;
    }
}
