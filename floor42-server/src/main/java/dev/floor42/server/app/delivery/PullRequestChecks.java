package dev.floor42.server.app.delivery;

import dev.floor42.domain.Actor;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PullRequest;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.delivery.PullRequests.GhStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What {@link PullRequests} reads on GitHub before it records or merges anything: the head commit, the check runs of
 * that exact commit (ADR-0084), the verification a merge needs, and the state read again after a merge.
 */
@ApplicationScoped
class PullRequestChecks {

    private final OfficeService office;
    private final PullRequestDraft drafts;
    private final PullRequestGit git;
    private final GhClient gh;
    /** How many times, and how far apart, the state is read again when GitHub does not answer after a merge. */
    private final int confirmAttempts;

    private final long confirmDelayMillis;

    @Inject
    PullRequestChecks(
            OfficeService office,
            PullRequestDraft drafts,
            PullRequestGit git,
            GhClient gh,
            @ConfigProperty(name = "floor42.gh.confirm-attempts", defaultValue = "5") int confirmAttempts,
            @ConfigProperty(name = "floor42.gh.confirm-delay-millis", defaultValue = "1000") long confirmDelayMillis) {
        this.confirmAttempts = confirmAttempts;
        this.confirmDelayMillis = confirmDelayMillis;
        this.office = office;
        this.drafts = drafts;
        this.git = git;
        this.gh = gh;
    }

    /** What gh needs to ask about one pull request: where, which repository, and that gh is signed in. */
    record Target(Path project, String repository) {}

    Target target(MissionId id, int number, Origin[] origin) {
        origin[0] = drafts.check(Actor.director(), id);
        PullRequest pr = office.query(c -> c.deliveries().pullRequest(id, number))
                .orElseThrow(() -> new DomainException("UNKNOWN_PULL_REQUEST", "No pull request #" + number));
        Path project = drafts.project(id);
        String repository = PullRequestGit.repositoryOf(git.remoteUrl(project, pr.remote()))
                .orElseThrow(() -> new DomainException(
                        "NOT_GITHUB", "The remote " + pr.remote() + " no longer names a GitHub repository"));
        requireSameRepository(pr, repository);
        GhStatus status = gh.status(project, repository);
        if (!status.authenticated()) {
            throw new DomainException(status.available() ? "GH_NOT_AUTHENTICATED" : "GH_MISSING", status.detail());
        }
        return new Target(project, repository);
    }

    private static final java.util.regex.Pattern PR_REPOSITORY =
            java.util.regex.Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)/pull/\\d+");

    /** The repository the pull request was opened on, from its URL: a remote re-pointed since is refused (ADR-0084). */
    private static void requireSameRepository(PullRequest pr, String repository) {
        java.util.regex.Matcher m = PR_REPOSITORY.matcher(pr.url() == null ? "" : pr.url());
        if (!m.matches() || !repository.equalsIgnoreCase(m.group(1) + "/" + m.group(2) + "/" + m.group(3))) {
            throw new DomainException(
                    "REMOTE_CHANGED",
                    "The remote " + pr.remote() + " no longer points at the repository of pull request #" + pr.number()
                            + " (" + pr.url() + "): Floor42 reads and merges nothing there");
        }
    }

    static DomainException failed(String what, GhCli.Result r) {
        return new DomainException(
                r.timedOut() ? "GH_TIMEOUT" : "GH_FAILED",
                what + (r.timedOut() ? " timed out" : " failed: " + r.said()));
    }

    String headOf(Target t, int number) {
        GhClient.Head h = gh.head(t.project(), t.repository(), number);
        if (h.sha() == null) throw failed("gh pr view " + number, h.answer());
        return h.sha();
    }

    /**
     * The checks of the exact commit the branch is at (ADR-0084): the head is read, the check runs of that commit are
     * read, then the head is read again, because a push in between would tie checks to a commit that is no longer
     * the head (HEAD_MOVED). Nothing is recorded by this.
     */
    record Read(String commit, String state, List<String> checks) {}

    Read readChecks(Target t, MissionId id, int number) {
        GhClient.Head first = gh.head(t.project(), t.repository(), number);
        if (first.sha() == null) throw failed("gh pr view " + number, first.answer());
        String commit = first.sha();
        GhClient.Checks runs = gh.checkRuns(t.project(), t.repository(), commit);
        if (runs.failure() != null)
            throw failed("reading the checks of " + PullRequests.shortHash(commit), runs.failure());
        String after = headOf(t, number);
        if (!after.equals(commit)) {
            throw new DomainException(
                    "HEAD_MOVED",
                    "The branch moved from " + PullRequests.shortHash(commit) + " to " + PullRequests.shortHash(after)
                            + " while Floor42 read the checks: read them again");
        }
        return new Read(commit, first.state(), withExpected(id, number, runs.lines()));
    }

    /**
     * A check that GitHub has not created yet is unseen, not absent: every name of the previous evidence of this pull
     * request that is missing now is PENDING (MISSING). A check removed on purpose stays pending until the director
     * merges on GitHub: Floor42 cannot tell the two apart (ADR-0084).
     */
    private List<String> withExpected(MissionId id, int number, List<String> lines) {
        List<String> all = new ArrayList<>(lines);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String l : lines) PullRequest.Check.parse(l).ifPresent(c -> seen.add(c.name()));
        office.query(c -> c.deliveries().evidence(id, number)).stream()
                .flatMap(e -> e.checks().stream())
                .map(PullRequest.Check::name)
                .filter(n -> !seen.contains(n))
                .forEach(n -> all.add(n + "|pending|MISSING"));
        return all;
    }

    /** The pull request's state after a merge; null when GitHub never answered within the attempts (unknown). */
    GhClient.Head confirmed(Target t, int number) {
        for (int i = 0; i < confirmAttempts; i++) {
            GhClient.Head h = gh.head(t.project(), t.repository(), number);
            if (h.sha() != null) return h;
            if (confirmDelayMillis > 0 && i + 1 < confirmAttempts) {
                try {
                    Thread.sleep(confirmDelayMillis);
                } catch (InterruptedException e) {
                    // Handled: the interrupt flag is restored and the state is reported unknown (null), as after the
                    // last attempt.
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    Verified verified(MissionId id, int number, String commit) {
        if (commit == null || commit.isBlank()) {
            throw new DomainException("INVALID_ARGUMENT", "Say which commit you looked at");
        }
        Origin[] origin = new Origin[1];
        Target t = target(id, number, origin);
        if (office.query(c -> c.deliveries().merge(id, number)).isPresent()) {
            throw new DomainException("PULL_REQUEST_MERGED", "Pull request #" + number + " is already merged");
        }
        Read read = readChecks(t, id, number);
        requireMergeable(read, number, commit);
        return new Verified(t, origin[0], read);
    }

    /** The pull request is open, at the commit the director looked at, and every check of it is green. */
    private static void requireMergeable(Read read, int number, String commit) {
        if ("MERGED".equals(read.state())) {
            throw new DomainException(
                    "PULL_REQUEST_MERGED", "Pull request #" + number + " is already merged on GitHub");
        }
        if (!"OPEN".equals(read.state())) {
            throw new DomainException(
                    "NOT_OPEN", "Pull request #" + number + " is " + read.state() + " on GitHub, not open");
        }
        if (!read.commit().equals(commit)) {
            throw new DomainException(
                    "EVIDENCE_STALE",
                    "The branch is at " + PullRequests.shortHash(read.commit()) + " now, not at "
                            + PullRequests.shortHash(commit) + ": read the checks of the new commit");
        }
        String conclusion = PullRequest.conclusionOf(read.checks());
        if (!PullRequest.GREEN.equals(conclusion)) {
            throw new DomainException(
                    "CHECKS_NOT_GREEN",
                    "The checks of " + PullRequests.shortHash(commit) + " are "
                            + conclusion.toLowerCase(java.util.Locale.ROOT)
                            + ", not green: Floor42 merges nothing else");
        }
    }

    record Verified(Target target, Origin origin, Read read) {}
}
