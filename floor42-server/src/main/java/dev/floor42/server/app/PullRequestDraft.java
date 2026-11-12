package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AgentId;
import dev.floor42.domain.DomainException;
import dev.floor42.domain.Intervention;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.Origin;
import dev.floor42.domain.Workspace;
import dev.floor42.server.app.PullRequests.GhStatus;
import dev.floor42.server.app.PullRequests.PullRequestPreview;
import dev.floor42.server.app.PullRequests.PullRequestView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a pull request would be (ADR-0036), read from the journal, git and gh, before anything is pushed: the branch,
 * its remote and GitHub repository, the base, the text, and the first reason Floor42 would refuse it. The refusals are
 * checked in a fixed order; the first one found is the one shown.
 */
@ApplicationScoped
public class PullRequestDraft {

    static final Set<String> PROTECTED = Set.of("main", "master");
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/][A-Za-z0-9._/-]*");

    /** A preview and what opening it needs. */
    record Prepared(PullRequestPreview preview, Origin origin, Path project, String repository) {}

    /** The first refusal found wins; later checks only add one when none was found before. */
    private static final class Refusal {
        String code;
        String why;

        void when(boolean condition, String code, String why) {
            if (this.code == null && condition) {
                this.code = code;
                this.why = why;
            }
        }

        boolean none() {
            return code == null;
        }
    }

    /** Which branch: an agent's (from the journal) or the director's own current one. */
    private record Side(AgentId workspace, String branch, String base, int pending) {}

    /** Where the branch goes: remote, its URL, and the GitHub repository it names (each null when unknown). */
    private record Target(String remote, String remoteUrl, String repository) {}

    private final OfficeService office;
    private final Workspaces workspaces;
    private final PullRequestGit git;
    private final GhClient gh;

    @Inject
    public PullRequestDraft(OfficeService office, Workspaces workspaces, PullRequestGit git, GhClient gh) {
        this.office = office;
        this.workspaces = workspaces;
        this.git = git;
        this.gh = gh;
    }

    /** Everything read before the lookup on GitHub, the refusals found so far in {@code refusal}. */
    private record Draft(
            Side side,
            String head,
            int commits,
            List<Workspaces.CommitLine> log,
            Target target,
            GhStatus status,
            String base) {}

    Prepared prepare(Actor actor, MissionId id, String agent) {
        Origin origin = check(actor, id);
        Path project = project(id);
        Refusal refusal = new Refusal();
        Draft d = draft(id, project, agent, refusal);
        PullRequestView existing = refusal.none()
                ? existing(project, d.target().repository(), d.side().branch(), refusal)
                : null;
        return new Prepared(
                preview(id, project, d, refusal, existing),
                origin,
                project,
                d.target().repository());
    }

    private Draft draft(MissionId id, Path project, String agent, Refusal refusal) {
        Side side = side(id, project, agent, refusal);
        String branch = side.branch();
        String head = branch.isEmpty() ? null : workspaces.resolve(project, "refs/heads/" + branch);
        checkBranch(branch, head, refusal);
        int commits = head == null || side.base() == null ? -1 : workspaces.count(project, side.base() + ".." + head);
        List<Workspaces.CommitLine> log = log(project, side.base(), head);
        if (head != null && side.base() != null) {
            dev.floor42.server.runtime.Confinement.configurationPath(
                            workspaces.changedPaths(project, side.base() + ".." + head))
                    .ifPresent(p -> refusal.when(
                            true,
                            "PROTECTED_PATH",
                            branch + " changes " + p + ", which configures Claude Code or MCP: never delivered"));
        }
        Target target = target(project, branch, refusal);
        GhStatus status = ghStatus(project, target.repository(), refusal);
        String base = status.authenticated() ? defaultBranch(project, target.repository(), refusal) : null;
        refusal.when(
                branch.equals(base),
                "HEAD_IS_DEFAULT",
                branch + " is the default branch of " + target.repository()
                        + ": a pull request is made from another branch, never from it");
        refusal.when(commits == 0, "NOTHING_TO_PROPOSE", branch + " holds no commit of its own");
        return new Draft(side, head, commits, log, target, status, base);
    }

    private PullRequestPreview preview(MissionId id, Path project, Draft d, Refusal refusal, PullRequestView existing) {
        Side side = d.side();
        List<String> changed =
                d.head() == null || side.base() == null ? List.of() : git.changedFiles(project, side.base(), d.head());
        PullRequestText.Text text =
                office.query(c -> PullRequestText.compose(c, id, side.workspace(), side.branch(), d.log(), changed));
        return new PullRequestPreview(
                id.value(),
                side.workspace() == null ? null : side.workspace().value(),
                side.branch(),
                d.head(),
                Math.max(d.commits(), d.log().size()),
                d.target().remote(),
                d.target().remoteUrl(),
                d.target().repository(),
                d.base(),
                text.title(),
                text.body(),
                d.status(),
                refusal.code,
                refusal.why,
                existing,
                side.pending());
    }

    /** The commits the text lists: those since the base, or the last ten of the director's own branch. */
    private List<Workspaces.CommitLine> log(Path project, String base, String head) {
        if (head == null) return List.of();
        return base == null ? workspaces.log(project, head, 10) : workspaces.log(project, base + ".." + head, 30);
    }

    private Side side(MissionId id, Path project, String agent, Refusal refusal) {
        AgentId workspace = agent == null || agent.isBlank() ? null : new AgentId(agent.strip());
        if (workspace == null) return new Side(null, git.currentBranch(project), null, 0);
        Workspace w = office.query(c -> c.workshop().workspace(id, workspace))
                .orElseThrow(() -> new DomainException("UNKNOWN_WORKSPACE", workspace + " has no workspace on " + id));
        // -1: git did not answer, the screen says unknown rather than "no uncommitted change" (ADR-0055).
        int pending =
                workspaces.pendingIfKnown(Path.of(w.path())).map(List::size).orElse(-1);
        boolean accepted = office.query(c -> c.workshop().interventions(id).stream()
                .anyMatch(i -> i.kind() == Intervention.Kind.PATCH_ACCEPTED && workspace.equals(i.workspace())));
        refusal.when(
                !accepted,
                "NOTHING_ACCEPTED",
                "Nothing of " + workspace + "'s work was accepted in the workshop yet: accept it first, a pull request"
                        + " carries accepted commits only");
        return new Side(workspace, w.branch(), w.baseCommit(), pending);
    }

    private static void checkBranch(String branch, String head, Refusal refusal) {
        refusal.when(
                branch.isEmpty() || head == null,
                "DETACHED_HEAD",
                "The project is on no branch: check out the branch to propose");
        refusal.when(
                !SAFE_REF.matcher(branch).matches() || branch.contains(".."),
                "UNSAFE_BRANCH",
                "Floor42 does not push a branch named " + branch);
        refusal.when(
                PROTECTED.contains(branch),
                "HEAD_IS_DEFAULT",
                branch + " is a default branch: a pull request is made from another branch, never from it");
    }

    private Target target(Path project, String branch, Refusal refusal) {
        String remote = git.remoteFor(project, branch);
        String remoteUrl = remote == null ? null : git.remoteUrl(project, remote);
        String repository = remoteUrl == null
                ? null
                : PullRequestGit.repositoryOf(remoteUrl).orElse(null);
        refusal.when(
                remote == null,
                "NO_REMOTE",
                "The repository has no remote to push to: add one (git remote add origin <url>)");
        refusal.when(
                remote != null && !SAFE_REF.matcher(remote).matches(),
                "UNSAFE_REMOTE",
                "Floor42 does not push to a remote named " + remote);
        refusal.when(
                repository == null,
                "NOT_GITHUB",
                "The remote " + remote + " (" + remoteUrl + ") does not name a GitHub repository");
        return new Target(remote, remoteUrl, repository);
    }

    private GhStatus ghStatus(Path project, String repository, Refusal refusal) {
        GhStatus status = repository == null
                ? new GhStatus(false, false, "Not asked: no GitHub repository to ask about", null)
                : gh.status(project, repository);
        if (!status.available()) refusal.when(true, "GH_MISSING", status.detail());
        else refusal.when(!status.authenticated(), "GH_NOT_AUTHENTICATED", status.detail());
        return status;
    }

    private String defaultBranch(Path project, String repository, Refusal refusal) {
        GhClient.DefaultBranch d = gh.defaultBranch(project, repository);
        GhCli.Result r = d.answer();
        refusal.when(
                d.name() == null,
                r.timedOut() ? "GH_TIMEOUT" : "GH_FAILED",
                "gh could not read " + repository + "'s default branch: " + (r.timedOut() ? "it timed out" : r.said()));
        return d.name();
    }

    private PullRequestView existing(Path project, String repository, String branch, Refusal refusal) {
        GhClient.Lookup found = gh.lookup(project, repository, branch);
        refusal.when(
                found.failed(),
                "GH_FAILED",
                "gh could not tell whether a pull request is already open for " + branch + ": " + found.said());
        return found.pr() == null ? null : PullRequestView.found(found.pr(), branch);
    }

    /** The mission's origin, once the domain lets this actor open a pull request on it. */
    Origin check(Actor actor, MissionId id) {
        Origin origin = office.query(c -> c.missions()
                .find(id)
                .orElseThrow(() -> new DomainException("UNKNOWN_MISSION", "No mission " + id))
                .origin());
        office.query(c -> {
            c.deliveries().checkPullRequest(office.context(actor, origin), id);
            return null;
        });
        return origin;
    }

    Path project(MissionId id) {
        Mission m = office.query(c -> c.missions().find(id).orElseThrow());
        return Path.of(m.workspace());
    }
}
