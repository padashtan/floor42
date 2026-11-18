package dev.floor42.server.app.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The director's own {@code gh}, as Floor42 asks it about pull requests (ADR-0036): signed in or not, the repository's
 * default branch, a pull request already open for a branch, opening one, its checks. Floor42 never signs in for the
 * director and never stores a token. Tests swap the command for a fake one.
 */
@ApplicationScoped
public class GhClient {

    private static final Logger LOG = Logger.getLogger(GhClient.class);

    /** A pull request found open on GitHub for a branch. */
    record Found(int number, String url, String base) {}

    /** What {@code gh pr list --head} answered: the one found, none, or a failure ({@code said} says why). */
    record Lookup(Found pr, boolean failed, String said) {}

    private final ObjectMapper json;
    private final List<String> command;
    private final String mergeMethod;
    private final long timeoutSeconds;
    private volatile GhCli gh;
    private volatile long limit;

    @Inject
    public GhClient(
            ObjectMapper json,
            @ConfigProperty(name = "floor42.gh.command", defaultValue = "gh") List<String> command,
            @ConfigProperty(name = "floor42.gh.timeout-seconds", defaultValue = "60") long timeoutSeconds,
            @ConfigProperty(name = "floor42.gh.merge-method", defaultValue = "squash") String mergeMethod) {
        this.json = json;
        this.mergeMethod = List.of("merge", "squash", "rebase").contains(mergeMethod) ? mergeMethod : "squash";
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
        this.gh = new GhCli.Process(command);
        this.limit = timeoutSeconds;
    }

    /** Tests swap gh for a fake one and shorten the limit; null puts the configured ones back. */
    void useForTests(GhCli cli, Long seconds) {
        gh = cli == null ? new GhCli.Process(command) : cli;
        limit = seconds == null ? timeoutSeconds : seconds;
    }

    GhCli.Result run(Path dir, String... args) {
        return gh.run(dir, List.of(args), limit);
    }

    PullRequests.GhStatus status(Path project, String repository) {
        String host = repository.substring(0, repository.indexOf('/'));
        GhCli.Result r = run(project, "auth", "status", "--hostname", host);
        if (r.missing()) {
            return new PullRequests.GhStatus(
                    false,
                    false,
                    "GitHub's command line (gh) is not installed or not on the PATH of Floor42's daemon. Install it"
                            + " (https://cli.github.com), sign in, then restart Floor42.",
                    "gh auth login");
        }
        if (r.timedOut()) {
            return new PullRequests.GhStatus(
                    true, false, "gh auth status timed out: Floor42 cannot tell whether gh is signed in", null);
        }
        if (r.code() != 0) {
            return new PullRequests.GhStatus(
                    true,
                    false,
                    "gh is not signed in to " + host + ". Run gh auth login in a terminal of yours: Floor42 never signs"
                            + " in for you and never stores a token.",
                    "gh auth login --hostname " + host);
        }
        return new PullRequests.GhStatus(true, true, "gh is signed in to " + host, null);
    }

    /** The repository's default branch and gh's answer; the name is null when gh could not read it. */
    record DefaultBranch(String name, GhCli.Result answer) {}

    DefaultBranch defaultBranch(Path project, String repository) {
        GhCli.Result r = run(project, "repo", "view", repository, "--json", "defaultBranchRef,url");
        String name = r.ok()
                ? object(r.out())
                        .map(n -> n.path("defaultBranchRef").path("name").asText(""))
                        .filter(s -> !s.isBlank())
                        .orElse(null)
                : null;
        return new DefaultBranch(name, r);
    }

    /** The pull request open for this branch on GitHub, if any: {@code gh pr list --head}. */
    Lookup lookup(Path project, String repository, String branch) {
        GhCli.Result r = run(
                project,
                "pr",
                "list",
                "--repo",
                repository,
                "--head",
                branch,
                "--state",
                "open",
                "--json",
                "number,url,headRefName,baseRefName");
        Optional<JsonNode> list = r.ok() ? array(r.out()) : Optional.empty();
        if (list.isEmpty()) return new Lookup(null, true, r.timedOut() ? "it timed out" : r.said());
        for (JsonNode n : list.get()) {
            if (!branch.equals(n.path("headRefName").asText(branch))) continue;
            int number = n.path("number").asInt(0);
            String url = n.path("url").asText("");
            if (number > 0 && !url.isBlank()) {
                return new Lookup(new Found(number, url, n.path("baseRefName").asText("")), false, null);
            }
        }
        return new Lookup(null, false, null);
    }

    /** {@code gh pr create}, the description passed in a temporary file deleted afterwards. */
    private static Path bodyFile(String body) {
        try {
            Path bodyFile = Files.createTempFile("floor42-pr-", ".md"); // NOSONAR owner-only temp
            Files.writeString(bodyFile, body, StandardCharsets.UTF_8);
            return bodyFile;
        } catch (IOException e) {
            throw new UncheckedIOException("Floor42 could not write the description", e);
        }
    }

    GhCli.Result create(Path project, String repository, String head, String base, String title, String body) {
        Path bodyFile = bodyFile(body);
        try {
            List<String> args =
                    List.of("pr", "create", "--repo", repository, "--head", head, "--base", base, "--title", title);
            return run(
                    project,
                    Stream.concat(args.stream(), Stream.of("--body-file", bodyFile.toString()))
                            .toArray(String[]::new));
        } finally {
            try {
                Files.deleteIfExists(bodyFile);
            } catch (IOException e) {
                // Handled: the pull request is made either way; a temporary copy of its text left behind is logged.
                LOG.debugf("Temporary description %s left behind: %s", bodyFile, e.getMessage());
            }
        }
    }

    /**
     * The commit a pull request's branch is at now, and whether the pull request is still open: {@code gh pr view}.
     *
     * @param sha null when gh gave none (failed, timed out, not JSON); {@code answer} says why
     */
    record Head(String sha, String state, GhCli.Result answer) {}

    Head head(Path project, String repository, int number) {
        GhCli.Result r = run(
                project, "pr", "view", Integer.toString(number), "--repo", repository, "--json", "headRefOid,state");
        Optional<JsonNode> n = r.ok() ? object(r.out()) : Optional.empty();
        String sha = n.map(o -> o.path("headRefOid").asText(""))
                .filter(x -> !x.isBlank())
                .orElse(null);
        return new Head(sha, n.map(o -> o.path("state").asText("")).orElse(""), r);
    }

    /**
     * The checks of one exact commit (ADR-0084), as {@code name|bucket|state} lines: the check runs GitHub keeps for
     * that sha ({@code gh pr checks} has no sha) and the legacy statuses of the same sha. A check is a check suite
     * (one workflow) and a name: its latest attempt, the run with the highest id, is the one that counts, so a
     * cancelled run superseded on the same commit is not a failure, while the same name in two workflows stays two
     * checks (the name then says which suite). Statuses count one by one: the combined state, pending with none
     * reported, is not read.
     */
    Checks checkRuns(Path project, String repository, String sha) {
        String host = repository.substring(0, repository.indexOf('/'));
        String repo = repository.substring(repository.indexOf('/') + 1);
        GhCli.Result r = run(
                project, "api", "--hostname", host, "repos/" + repo + "/commits/" + sha + "/check-runs?per_page=100");
        Optional<JsonNode> body = r.ok() ? object(r.out()) : Optional.empty();
        if (body.isEmpty() || !body.get().path("check_runs").isArray()) return new Checks(List.of(), r);
        JsonNode runs = body.get().path("check_runs");
        if (body.get().path("total_count").asInt(runs.size()) > runs.size()) {
            // shortcut: no paging, a commit with over 100 check runs is refused rather than half read; page when seen.
            return new Checks(List.of(), new GhCli.Result(1, "", "more than 100 check runs on " + sha, false, false));
        }
        java.util.Map<String, Integer> shown = new java.util.HashMap<>();
        List<String> lines = runLines(runs, shown);
        GhCli.Result st =
                run(project, "api", "--hostname", host, "repos/" + repo + "/commits/" + sha + "/status?per_page=100");
        Optional<JsonNode> combined = st.ok() ? object(st.out()) : Optional.empty();
        if (combined.isEmpty() || !combined.get().path("statuses").isArray()) return new Checks(List.of(), st);
        JsonNode statuses = combined.get().path("statuses");
        if (combined.get().path("total_count").asInt(statuses.size()) > statuses.size()) {
            // shortcut: no paging, a commit with over 100 statuses is refused rather than half read; page when seen.
            return new Checks(List.of(), new GhCli.Result(1, "", "more than 100 statuses on " + sha, false, false));
        }
        addStatusLines(statuses, shown, lines);
        return new Checks(lines, null);
    }

    /** The legacy statuses as lines, one by one; a context that is also a check run is labelled "(status)". */
    private static void addStatusLines(JsonNode statuses, java.util.Map<String, Integer> shown, List<String> lines) {
        for (JsonNode n : statuses) {
            String context = n.path("context").asText("").replace('|', '/').strip();
            if (context.isEmpty()) continue;
            String state = n.path("state").asText("");
            String bucket = state.equals("success") ? "pass" : state.equals("pending") ? "pending" : "fail";
            lines.add((shown.containsKey(context) ? context + " (status)" : context) + "|" + bucket + "|"
                    + state.toUpperCase(java.util.Locale.ROOT).replace('|', '/'));
        }
    }

    /** The latest run of each check suite and name as lines; {@code shown} counts the labels written. */
    private static List<String> runLines(JsonNode runs, java.util.Map<String, Integer> shown) {
        java.util.Map<String, JsonNode> latest = new java.util.TreeMap<>();
        java.util.Map<String, java.util.Set<Long>> suitesOf = new java.util.HashMap<>();
        for (JsonNode n : runs) {
            String name = n.path("name").asText("").replace('|', '/').strip();
            if (name.isEmpty()) continue;
            long suite = n.path("check_suite")
                    .path("id")
                    .asLong(n.path("app").path("id").asLong(0));
            latest.merge(
                    suite + " " + name,
                    n,
                    (x, y) -> y.path("id").asLong() > x.path("id").asLong() ? y : x);
            suitesOf.computeIfAbsent(name, k -> new java.util.HashSet<>()).add(suite);
        }
        List<String> lines = new ArrayList<>();
        // Names of one suite sort together; a name that lives in several suites says which one it is.
        latest.forEach((key, n) -> {
            String name = key.substring(key.indexOf(' ') + 1);
            String suite = key.substring(0, key.indexOf(' '));
            String label = suitesOf.get(name).size() > 1 ? name + " (suite " + suite + ")" : name;
            shown.merge(label, 1, Integer::sum);
            lines.add(label + "|" + bucketOf(n) + "|" + stateOf(n));
        });
        return lines;
    }

    private static String stateOf(JsonNode run) {
        String status = run.path("status").asText("");
        String shown = status.equals("completed") ? run.path("conclusion").asText("") : status;
        return shown.toUpperCase(java.util.Locale.ROOT).replace('|', '/');
    }

    /** gh's own buckets: a run not finished is pending; anything unknown is not a pass. */
    private static String bucketOf(JsonNode run) {
        if (!run.path("status").asText("").equals("completed")) return "pending";
        return switch (run.path("conclusion").asText("")) {
            case "success" -> "pass";
            case "neutral", "skipped" -> "skipping";
            case "cancelled" -> "cancel";
            default -> "fail";
        };
    }

    /** {@code gh pr merge} at exactly {@code sha}: GitHub refuses when the branch is no longer there. */
    GhCli.Result merge(Path project, String repository, int number, String sha) {
        return run(
                project,
                "pr",
                "merge",
                Integer.toString(number),
                "--repo",
                repository,
                "--" + mergeMethod,
                "--match-head-commit",
                sha);
    }

    /** @param failure gh's answer when it gave no list (and did not say there are no checks); null otherwise */
    record Checks(List<String> lines, GhCli.Result failure) {}

    private Optional<JsonNode> array(String text) {
        return tree(text).filter(JsonNode::isArray);
    }

    private Optional<JsonNode> object(String text) {
        return tree(text).filter(JsonNode::isObject);
    }

    private Optional<JsonNode> tree(String text) {
        try {
            return Optional.ofNullable(json.readTree(text == null || text.isBlank() ? "null" : text));
        } catch (JsonProcessingException e) {
            // Handled: gh answered something that is not JSON; the caller reports what gh said instead.
            LOG.debugf("gh answered no JSON: %s", e.getOriginalMessage());
            return Optional.empty();
        }
    }
}
