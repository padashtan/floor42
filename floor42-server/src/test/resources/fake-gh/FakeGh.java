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
 * (properties: {@code auth=yes|no}, {@code default=main}, {@code create=ok|timeout|fail}); the fake writes {@code prs.tsv}, {@code body.md} and appends every call to {@code calls.log}.
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
        if (args.get(0).equals("api")) command = "api";
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
            case "pr view" -> {
                Path head = state.resolve("head.txt");
                if (!Files.exists(head)) {
                    System.err.println("no pull request found");
                    System.exit(1);
                }
                // After the merge, the first N reads fail: GitHub answers late.
                Path fails = state.resolve("view-fails");
                if (Files.exists(state.resolve("merged")) && Files.exists(fails)) {
                    int left = Integer.parseInt(Files.readString(fails).strip());
                    if (left != 0) {
                        if (left > 0) Files.writeString(fails, Integer.toString(left - 1));
                        System.err.println("HTTP 502: Bad Gateway");
                        System.exit(1);
                    }
                }
                String shown = Files.exists(state.resolve("merged")) ? "MERGED" : "OPEN";
                if (Files.exists(state.resolve("state.txt"))) shown = Files.readString(state.resolve("state.txt")).strip();
                System.out.println("{\"headRefOid\":\"" + Files.readString(head).strip() + "\",\"state\":\"" + shown
                        + "\"}");
            }
            case "api" -> {
                // gh api repos/<o>/<r>/commits/<sha>/check-runs or /status: what GitHub keeps for THAT commit only.
                String path = args.get(args.size() - 1);
                String sha = path.replaceAll(".*/commits/([0-9a-f]+)/(check-runs|status).*", "$1");
                boolean status = path.contains("/status");
                Path found = state.resolve((status ? "status-" : "check-runs-") + sha + ".json");
                System.out.println(Files.exists(found)
                        ? Files.readString(found, StandardCharsets.UTF_8)
                        : status
                                ? "{\"state\":\"pending\",\"total_count\":0,\"statuses\":[]}"
                                : "{\"total_count\":0,\"check_runs\":[]}");
                // The branch moves while Floor42 reads: the next question about its head sees the new commit.
                Path after = state.resolve("head-after-checks.txt");
                if (Files.exists(after)) {
                    Files.move(after, state.resolve("head.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            case "pr merge" -> {
                String mode = scenario.getProperty("merge", "ok");
                if (mode.equals("slow")) Thread.sleep(3_000);
                if (mode.equals("fail")) {
                    System.err.println("Pull request is not mergeable: the base branch policy prohibits the merge");
                    System.exit(1);
                }
                // ok-but-open: auto-merge queued, gh is happy and GitHub has not merged yet.
                if (!mode.equals("ok-but-open")) Files.writeString(state.resolve("merged"), String.join(" ", args));
                if (mode.equals("fail-but-merged")) {
                    System.err.println("HTTP 504: timeout");
                    System.exit(1);
                }
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
