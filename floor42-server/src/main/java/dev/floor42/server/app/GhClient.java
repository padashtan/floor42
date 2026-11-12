package dev.floor42.server.app;

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
    private final long timeoutSeconds;
    private volatile GhCli gh;
    private volatile long limit;

    @Inject
    public GhClient(
            ObjectMapper json,
            @ConfigProperty(name = "floor42.gh.command", defaultValue = "gh") List<String> command,
            @ConfigProperty(name = "floor42.gh.timeout-seconds", defaultValue = "60") long timeoutSeconds) {
        this.json = json;
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
     * The checks of a pull request as {@code name|bucket|state} lines; empty when gh reports none. gh exits 1 when a
     * check failed and 8 while some are pending: the list is in its output all the same.
     */
    Checks checks(Path project, String repository, int number) {
        GhCli.Result r = run(
                project, "pr", "checks", Integer.toString(number), "--repo", repository, "--json", "name,bucket,state");
        Optional<JsonNode> list = r.timedOut() || r.missing() ? Optional.empty() : array(r.out());
        if (list.isEmpty()) {
            boolean none = r.said().toLowerCase(java.util.Locale.ROOT).contains("no checks reported");
            return new Checks(List.of(), none ? null : r);
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode n : list.get()) {
            String name = n.path("name").asText("").replace('|', '/').strip();
            String bucket = n.path("bucket").asText("").replace('|', '/').strip();
            if (name.isEmpty() || bucket.isEmpty()) continue;
            lines.add(name + "|" + bucket + "|"
                    + n.path("state").asText("").replace('|', '/').strip());
        }
        return new Checks(lines, null);
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
