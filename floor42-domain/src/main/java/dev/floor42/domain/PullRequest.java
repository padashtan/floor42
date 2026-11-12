package dev.floor42.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A pull request Floor42 opened on GitHub for a mission, on the director's click (ADR-0036), with the last checks the
 * director asked GitHub for.
 *
 * @param workspace the agent whose branch it carries; null for the director's own branch of the project
 * @param found Floor42 found it with {@code gh pr list} after a failed or timed-out attempt, rather than from
 *     {@code gh pr create}'s answer
 * @param checks the last checks read, empty when none was read or GitHub reported none
 * @param checksAt when they were read; null when never
 */
public record PullRequest(
        MissionId mission,
        AgentId workspace,
        String url,
        int number,
        String branch,
        String base,
        String remote,
        boolean found,
        Instant openedAt,
        List<Check> checks,
        Instant checksAt) {

    /** One check as GitHub reports it: its name, its bucket (pass, fail, pending, skipping, cancel) and its state. */
    public record Check(String name, String bucket, String state) {

        /** A line {@code name|bucket|state}; empty when it is not one. */
        public static Optional<Check> parse(String line) {
            if (line == null) return Optional.empty();
            String[] p = line.split("\\|", -1);
            if (p.length != 3 || p[0].isBlank() || p[1].isBlank()) return Optional.empty();
            return Optional.of(new Check(p[0], p[1], p[2]));
        }

        public String line() {
            return name + "|" + bucket + "|" + state;
        }
    }

    public PullRequest {
        checks = List.copyOf(checks);
    }

    PullRequest withChecks(List<Check> read, Instant at) {
        return new PullRequest(mission, workspace, url, number, branch, base, remote, found, openedAt, read, at);
    }
}
