package dev.floor42.server.app.delivery;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PullRequest;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.runtime.GitProcess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pull requests on GitHub, on the director's click only (ADR-0036): Floor42 pushes a branch of the mission to the
 * repository's remote, never forced, never the default branch, then opens the pull request with the director's own
 * {@code gh}. A preview shows remote, branch, base, title and body first; nothing leaves the machine before the
 * director confirms it. After an error or a timeout Floor42 looks on GitHub before saying anything failed.
 *
 * <p>Git's side is {@link PullRequestGit}, gh's is {@link GhClient}; this service decides and records.
 */
@ApplicationScoped
public class PullRequests {

    /** What Floor42 knows of gh on this machine: installed, signed in. Floor42 never signs in for the director. */
    public record GhStatus(boolean available, boolean authenticated, String detail, String fix) {}

    /** One check GitHub reports on a pull request. */
    public record PullRequestCheck(String name, String bucket, String state) {}

    public record PullRequestView(
            int number,
            String url,
            String workspace,
            String branch,
            String base,
            String remote,
            boolean found,
            String openedAt,
            List<PullRequestCheck> checks,
            String checksAt,
            String commit,
            String conclusion,
            Boolean stale,
            String headNow,
            String mergedCommit,
            String mergedAt) {
        public PullRequestView {
            checks = checks == null ? null : Collections.unmodifiableList(new ArrayList<>(checks));
        }

        static PullRequestView of(PullRequest p, PullRequest.Evidence e, PullRequest.Merge m, String headNow) {
            return new PullRequestView(
                    p.number(),
                    p.url(),
                    p.workspace() == null ? null : p.workspace().value(),
                    p.branch(),
                    p.base(),
                    p.remote(),
                    p.found(),
                    p.openedAt().toString(),
                    p.checks().stream()
                            .map(c -> new PullRequestCheck(c.name(), c.bucket(), c.state()))
                            .toList(),
                    p.checksAt() == null ? null : p.checksAt().toString(),
                    e == null ? null : e.commit(),
                    e == null ? null : e.conclusion(),
                    e == null || headNow == null ? null : !e.commit().equals(headNow),
                    headNow,
                    m == null ? null : m.commit(),
                    m == null ? null : m.at().toString());
        }

        static PullRequestView found(GhClient.Found f, String branch) {
            return new PullRequestView(
                    f.number(),
                    f.url(),
                    null,
                    branch,
                    f.base(),
                    null,
                    true,
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /**
     * What a pull request would be, before anything is pushed: the director reads it in the confirmation dialog.
     *
     * @param workspace the agent whose branch it carries; null for the director's own branch of the project
     * @param head the commit the branch is at
     * @param repository the GitHub repository named by the remote, {@code host/owner/name}
     * @param refused why Floor42 will not open it now (a stable code), null when it can; {@code refusal} says it
     * @param existing a pull request already open for this branch on GitHub, found with {@code gh pr list}
     * @param pending uncommitted changes in the worktree, -1 when git could not tell: they are not in the pull request
     */
    public record PullRequestPreview(
            String mission,
            String workspace,
            String branch,
            String head,
            int commits,
            String remote,
            String remoteUrl,
            String repository,
            String base,
            String title,
            String body,
            GhStatus gh,
            String refused,
            String refusal,
            PullRequestView existing,
            int pending) {}

    /** What the director confirmed: Floor42 pushes only if it still matches what it would push. */
    public record PullRequestRequest(
            String workspace, String remote, String branch, String base, String title, String body, String remoteUrl) {}

    /**
     * @param outcome OPENED (gh created it), FOUND (it exists on GitHub: found after an error or already open), FAILED
     *     (Floor42 looked and found none: a retry is safe), UNKNOWN (Floor42 could not look: check on GitHub first)
     * @param pushed whether the branch is on the remote at its commit; null when Floor42 could not tell
     */
    public record PullRequestOutcome(
            String outcome, Boolean pushed, PullRequestView pullRequest, String detail, boolean retry) {}

    static final int MAX_BODY = 60_000;
    private static final Pattern PR_URL = Pattern.compile("(https?://\\S+/pull/(\\d+))");

    private final OfficeService office;
    private final PullRequestDraft drafts;
    private final PullRequestGit git;
    private final GhClient gh;
    private final PullRequestChecks checks;
    private final Set<MissionId> running = ConcurrentHashMap.newKeySet();

    @Inject
    public PullRequests(
            OfficeService office, PullRequestDraft drafts, PullRequestGit git, GhClient gh, PullRequestChecks checks) {
        this.office = office;
        this.drafts = drafts;
        this.git = git;
        this.gh = gh;
        this.checks = checks;
    }

    /** Tests swap gh for a fake one and shorten the limit; null puts the configured ones back. */
    public void useForTests(GhCli cli, Long seconds) {
        gh.useForTests(cli, seconds);
    }

    /** The pull requests recorded for the mission. Reads the journal only: no call to GitHub. */
    public List<PullRequestView> list(MissionId id) {
        return office.query(c -> c.deliveries().pullRequests(id).stream()
                .map(p -> view(c, p, null))
                .toList());
    }

    /** What a pull request of this branch would be. Pushes nothing, changes nothing on GitHub. */
    public PullRequestPreview preview(Actor actor, MissionId id, String agent) {
        return drafts.prepare(actor, id, agent).preview();
    }

    /**
     * Pushes the branch (never forced) and opens the pull request the director confirmed. Before anything, Floor42 looks
     * for a pull request already open for the branch: a retry never opens a second one.
     */
    public PullRequestOutcome open(Actor actor, MissionId id, PullRequestRequest request) {
        if (request == null) throw new DomainException("INVALID_ARGUMENT", "Which pull request?");
        PullRequestDraft.Prepared p = drafts.prepare(actor, id, request.workspace());
        PullRequestPreview v = p.preview();
        if (v.refused() != null) throw new DomainException(v.refused(), v.refusal());
        if (!v.branch().equals(request.branch())
                || !v.remote().equals(request.remote())
                || !v.base().equals(request.base())
                || !java.util.Objects.equals(v.remoteUrl(), request.remoteUrl())) {
            throw new DomainException(
                    "PREVIEW_STALE",
                    "The branch, remote, its URL or base changed since the preview: Floor42 would push " + v.branch()
                            + " to " + v.remote() + " against " + v.base() + ". Look at the preview again.");
        }
        String title = request.title() == null ? "" : request.title().strip();
        String body = request.body() == null ? "" : request.body().strip();
        if (title.isEmpty()) throw new DomainException("INVALID_ARGUMENT", "A pull request needs a title");
        if (body.isEmpty()) throw new DomainException("INVALID_ARGUMENT", "A pull request needs a description");
        if (body.length() > MAX_BODY) throw new DomainException("INVALID_ARGUMENT", "The description is too long");
        if (!running.add(id)) {
            throw new DomainException("PULL_REQUEST_RUNNING", "A pull request of " + id + " is being opened");
        }
        try {
            return pushAndOpen(p, title, body);
        } finally {
            running.remove(id);
        }
    }

    /** The pull request as the journal knows it, with the head GitHub reported if one was read just now. */
    private static PullRequestView view(dev.floor42.domain.Company c, PullRequest p, String headNow) {
        return PullRequestView.of(
                p,
                c.deliveries().evidence(p.mission(), p.number()).orElse(null),
                c.deliveries().merge(p.mission(), p.number()).orElse(null),
                headNow);
    }

    private PullRequestView viewOf(MissionId id, int number, String headNow) {
        return office.query(c -> view(c, c.deliveries().pullRequest(id, number).orElseThrow(), headNow));
    }

    /** Reads the checks of the PR's head commit, once, on the director's click, and records them with the commit. */
    public PullRequestView refreshChecks(MissionId id, int number) {
        // The same guard as the merge: no read of the checks lands in the journal while a merge is under way.
        if (!running.add(id)) throw new DomainException("PULL_REQUEST_RUNNING", "A pull request of " + id + " is busy");
        try {
            Origin[] origin = new Origin[1];
            PullRequestChecks.Target t = checks.target(id, number, origin);
            PullRequestChecks.Read read = checks.readChecks(t, id, number);
            office.execute(c -> c.deliveries()
                    .recordDeliveryEvidence(
                            office.context(Actor.director(), origin[0]), id, number, read.commit(), read.checks()));
            return viewOf(id, number, read.commit());
        } finally {
            running.remove(id);
        }
    }

    /** Asks GitHub where the branch is now, changes nothing: the evidence is stale once the head is another commit. */
    public PullRequestView head(MissionId id, int number) {
        Origin[] origin = new Origin[1];
        PullRequestChecks.Target t = checks.target(id, number, origin);
        return viewOf(id, number, checks.headOf(t, number));
    }

    public record MergeRequest(String commit) {}

    /**
     * What a merge needs before the director is asked for their phone: the commit they looked at is the head now, and
     * its checks are all green. 409 {@code EVIDENCE_STALE}, {@code CHECKS_NOT_GREEN}, {@code PULL_REQUEST_MERGED}.
     * Reads GitHub, records nothing.
     */
    public void checkMerge(MissionId id, int number, String commit) {
        checks.verified(id, number, commit);
    }

    /**
     * Merges on GitHub, as the director's gh, only for the commit whose checks were just read green and that the
     * director looked at; {@code approval} names the signed act or the click that allowed it. gh is told the commit
     * ({@code --match-head-commit}); afterwards Floor42 asks GitHub whether the pull request is merged before it says so.
     */
    public PullRequestView merge(MissionId id, int number, String commit, String approval) {
        if (!running.add(id)) throw new DomainException("PULL_REQUEST_RUNNING", "A pull request of " + id + " is busy");
        try {
            PullRequestChecks.Verified v = checks.verified(id, number, commit);
            office.execute(c -> c.deliveries()
                    .recordDeliveryEvidence(
                            office.context(Actor.director(), v.origin()),
                            id,
                            number,
                            v.read().commit(),
                            v.read().checks()));
            GhCli.Result merged = gh.merge(v.target().project(), v.target().repository(), number, commit);
            checkMerged(v.target(), number, merged);
            recordMerged(v, id, number, commit, approval);
            return viewOf(id, number, commit);
        } finally {
            running.remove(id);
        }
    }

    /** GitHub, not gh, says whether the pull request is merged: both must agree before Floor42 records it. */
    private void checkMerged(PullRequestChecks.Target target, int number, GhCli.Result merged) {
        GhClient.Head after = checks.confirmed(target, number);
        if (after == null) {
            throw new DomainException(
                    "MERGE_UNKNOWN",
                    "Floor42 could not read the state of pull request #" + number
                            + " after asking gh to merge it: look at it on GitHub before trying again");
        }
        boolean merged2 = "MERGED".equals(after.state());
        if (!merged2) {
            if (merged.ok()) {
                throw new DomainException(
                        "GH_FAILED",
                        "gh answered for pull request #" + number
                                + " but GitHub does not report it merged: look at it on GitHub");
            }
            throw PullRequestChecks.failed("gh pr merge " + number, merged);
        }
        if (!merged.ok()) {
            throw new DomainException(
                    "GH_FAILED",
                    "gh reported a failure for pull request #" + number + " (" + merged.said()
                            + ") yet GitHub shows it merged: look at it on GitHub");
        }
    }

    private void recordMerged(PullRequestChecks.Verified v, MissionId id, int number, String commit, String approval) {
        office.execute(c -> c.deliveries()
                .recordPullRequestMerged(office.context(Actor.director(), v.origin()), id, number, commit, approval));
    }

    // --- pushing and opening -----------------------------------------------------------------------------------------

    private PullRequestOutcome pushAndOpen(PullRequestDraft.Prepared p, String title, String body) {
        PullRequestPreview v = p.preview();
        if (v.existing() != null) {
            PullRequestView recorded =
                    record(p, v.existing().number(), v.existing().url(), true);
            return new PullRequestOutcome(
                    "FOUND",
                    git.pushedAt(p.project(), v.remote(), v.branch(), v.head()),
                    recorded,
                    "A pull request is already open for " + v.branch() + ": nothing was pushed, nothing opened",
                    false);
        }
        PullRequestOutcome notPushed = push(p);
        if (notPushed != null) return notPushed;
        GhCli.Result create;
        try {
            create = gh.create(p.project(), p.repository(), v.branch(), v.base(), title, body);
        } catch (UncheckedIOException e) {
            // Handled: nothing was asked of GitHub yet; the director is told the branch is pushed and may retry.
            return new PullRequestOutcome(
                    "FAILED", true, null, "The branch is pushed; Floor42 could not write the description", true);
        }
        Matcher url = PR_URL.matcher(create.out() == null ? "" : create.out());
        if (create.ok() && url.find()) {
            PullRequestView recorded = record(p, Integer.parseInt(url.group(2)), url.group(1), false);
            return new PullRequestOutcome("OPENED", true, recorded, null, false);
        }
        return afterFailedCreate(p, create);
    }

    /** Pushes the branch; the outcome to return when it is not on the remote at its commit afterwards, else null. */
    private PullRequestOutcome push(PullRequestDraft.Prepared p) {
        PullRequestPreview v = p.preview();
        GitProcess.Result push = git.push(p.project(), v.remote(), v.remoteUrl(), v.branch());
        if (!push.timedOut() && push.code() == 0) return null;
        Boolean pushed = git.pushedAt(p.project(), v.remote(), v.branch(), v.head());
        if (Boolean.TRUE.equals(pushed)) return null;
        String said = push.err().isBlank() ? push.out().strip() : push.err().strip();
        return new PullRequestOutcome(
                "FAILED",
                pushed,
                null,
                "git push " + (push.timedOut() ? "timed out" : "failed: " + said)
                        + (pushed == null
                                ? ". Floor42 could not read the remote to tell whether the branch arrived."
                                : ". The branch is not on " + v.remote() + " at " + shortHash(v.head()) + "."),
                true);
    }

    /** gh failed, timed out or answered without a link: what happened on GitHub is unknown until Floor42 looks. */
    private PullRequestOutcome afterFailedCreate(PullRequestDraft.Prepared p, GhCli.Result create) {
        String branch = p.preview().branch();
        String why = create.timedOut() ? "gh pr create timed out" : "gh pr create failed: " + create.said();
        GhClient.Lookup found = gh.lookup(p.project(), p.repository(), branch);
        if (found.pr() != null) {
            PullRequestView recorded = record(p, found.pr().number(), found.pr().url(), true);
            return new PullRequestOutcome(
                    "FOUND", true, recorded, why + "; Floor42 looked on GitHub and found the pull request", false);
        }
        if (found.failed()) {
            return new PullRequestOutcome(
                    "UNKNOWN",
                    true,
                    null,
                    why + "; Floor42 could not look on GitHub (" + found.said()
                            + "): check the repository's pull requests before trying again",
                    false);
        }
        return new PullRequestOutcome(
                "FAILED",
                true,
                null,
                why + "; Floor42 looked on GitHub: no pull request is open for " + branch + ", a retry is safe",
                true);
    }

    private PullRequestView record(PullRequestDraft.Prepared p, int number, String url, boolean found) {
        PullRequestPreview v = p.preview();
        MissionId id = new MissionId(v.mission());
        AgentId workspace = v.workspace() == null ? null : new AgentId(v.workspace());
        boolean known = office.query(c -> c.deliveries().pullRequest(id, number).isPresent());
        if (!known) {
            office.execute(c -> c.deliveries()
                    .recordPullRequest(
                            office.context(Actor.director(), p.origin()),
                            id,
                            workspace,
                            url,
                            number,
                            v.branch(),
                            v.base(),
                            v.remote(),
                            found));
        }
        return viewOf(id, number, null);
    }

    static String shortHash(String hash) {
        return hash == null ? "?" : hash.substring(0, Math.min(8, hash.length()));
    }
}
