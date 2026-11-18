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

    /** The conclusion of a set of checks (ADR-0084): only GREEN lets a merge through. */
    public static final String GREEN = "GREEN";

    public static final String FAILED = "FAILED";
    public static final String PENDING = "PENDING";
    public static final String NONE = "NONE";

    /** The checks of one exact commit, as read from GitHub on the director's click. */
    public record Evidence(String commit, List<Check> checks, String conclusion, Instant at) {
        public Evidence {
            checks = List.copyOf(checks);
        }
    }

    /** The merge on GitHub: the commit that was merged and the signed act that allowed it. */
    public record Merge(String commit, String approval, Instant at) {}

    /**
     * GREEN when every check passed or was skipped, FAILED when one failed or was cancelled (a cancelled run is not a
     * pass), else PENDING; NONE when no check passed (none at all, or only skipped ones: nothing proves the commit).
     */
    public static String conclusionOf(List<String> lines) {
        if (lines == null || lines.isEmpty()) return NONE;
        boolean pending = false;
        int passed = 0;
        for (String l : lines) {
            String bucket = Check.parse(l).map(Check::bucket).orElse("fail");
            if (bucket.equals("pass")) passed++;
            else if (bucket.equals("pending")) pending = true;
            else if (!bucket.equals("skipping")) return FAILED;
        }
        // Skipped checks prove nothing: with no pass at all there is no evidence.
        if (pending) return PENDING;
        return passed == 0 ? NONE : GREEN;
    }

    public PullRequest {
        checks = List.copyOf(checks);
    }

    PullRequest withChecks(List<Check> read, Instant at) {
        return new PullRequest(mission, workspace, url, number, branch, base, remote, found, openedAt, read, at);
    }
}
