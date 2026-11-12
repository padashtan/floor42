package dev.floor42.domain;

import static dev.floor42.domain.DomainException.require;
import static dev.floor42.domain.Rules.requireReal;
import static dev.floor42.domain.Rules.requireText;

import dev.floor42.domain.event.DeliveryEvent;
import dev.floor42.domain.event.DeliveryEvent.DeliverablesCopied;
import dev.floor42.domain.event.DeliveryEvent.DeliverablesDestinationSet;
import dev.floor42.domain.event.DeliveryEvent.MissionAssetsSelected;
import dev.floor42.domain.event.DeliveryEvent.PluginCommandRan;
import dev.floor42.domain.event.DeliveryEvent.ProofRan;
import dev.floor42.domain.event.DeliveryEvent.ProofStepWitnessed;
import dev.floor42.domain.event.DeliveryEvent.ProofVerdictGiven;
import dev.floor42.domain.event.DeliveryEvent.PullRequestCheckState;
import dev.floor42.domain.event.DeliveryEvent.PullRequestOpened;
import dev.floor42.domain.event.DeliveryEvent.UncommittedWorkAccepted;
import dev.floor42.domain.event.DeliveryEvent.UnprovenDeliveryAccepted;
import dev.floor42.domain.event.EventEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What leaves the machine or comes into a mission's sessions on the director's say-so: pull requests (ADR-0036), the
 * skills and agents chosen for a mission and the plugin commands run for them (ADR-0040).
 */
final class Deliveries {

    /** {@code floor42:pr-review@claude-plugins-official/agent/code-reviewer}. */
    private static final Pattern ASSET_ITEM = Pattern.compile(
            "(floor42|claude):[A-Za-z0-9._-]{1,100}@[A-Za-z0-9._-]{1,100}/(skill|agent)/[A-Za-z0-9._-]{1,120}");

    private static final Set<String> PLUGIN_TARGETS = Set.of("FLOOR42", "CLAUDE");
    private static final Set<String> PLUGIN_ACTIONS = Set.of("INSTALL", "UNINSTALL");

    private final CompanyView view;
    private final List<PullRequest> pullRequests = new ArrayList<>();
    /** What the director chose to give each mission's sessions (ADR-0040), by mission. */
    private final Map<MissionId, List<String>> missionAssets = new HashMap<>();
    /** Where each mission's accepted deliverables are copied (ADR-0051), and the copy once made. */
    private final Map<MissionId, String> destinations = new HashMap<>();

    private final Map<MissionId, DeliveryCopy.Done> copies = new HashMap<>();
    /** The worktrees that were not clean when the director accepted the mission anyway (ADR-0055). */
    private final Map<MissionId, List<UncommittedWorktree>> uncommittedAccepted = new HashMap<>();

    /** An absolute folder as Windows or a POSIX system writes it: a drive and a separator, a share, or a slash. */
    private static final Pattern ABSOLUTE = Pattern.compile("([A-Za-z]:[\\\\/]|\\\\\\\\|/).*");

    /** A delivery's proof (ADR-0058). */
    final Proofs proofs;

    Deliveries(CompanyView view) {
        this.view = view;
        this.proofs = new Proofs(view);
    }

    List<PullRequest> pullRequests(MissionId mission) {
        return pullRequests.stream().filter(p -> p.mission().equals(mission)).toList();
    }

    Optional<PullRequest> pullRequest(MissionId mission, int number) {
        return pullRequests(mission).stream().filter(p -> p.number() == number).findFirst();
    }

    List<String> missionAssets(MissionId mission) {
        return missionAssets.getOrDefault(mission, List.of());
    }

    Optional<String> destination(MissionId mission) {
        return Optional.ofNullable(destinations.get(mission));
    }

    Optional<DeliveryCopy.Done> copy(MissionId mission) {
        return Optional.ofNullable(copies.get(mission));
    }

    List<UncommittedWorktree> uncommittedAccepted(MissionId mission) {
        return uncommittedAccepted.getOrDefault(mission, List.of());
    }

    /** The director accepts a mission whose worktrees hold uncommitted work, or whose state Floor42 could not read. */
    List<EventEnvelope> acceptUncommitted(CommandContext ctx, MissionId id, List<UncommittedWorktree> worktrees) {
        require(ctx.actor().isDirector(), Refusal.FORBIDDEN, "Only the director accepts uncommitted work");
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is over");
        List<UncommittedWorktree> list = worktrees == null ? List.of() : List.copyOf(worktrees);
        require(!list.isEmpty(), Refusal.INVALID_ARGUMENT, "Nothing uncommitted to accept");
        require(
                list.size() <= DeliveryCopy.MAX_FILES && list.stream().allMatch(UncommittedWorktree::wellFormed),
                Refusal.INVALID_ARGUMENT,
                "Each worktree is DIRTY with files, or UNKNOWN with a reason");
        return view.record(ctx, id).emit(new UncommittedWorkAccepted(id, list)).events();
    }

    /**
     * The director's folder for the mission's accepted deliverables (ADR-0051): an absolute path Floor42 resolved and
     * checked on disk (the server's part); here only its form, and who may set it on which mission.
     */
    List<EventEnvelope> setDestination(CommandContext ctx, MissionId id, String folder) {
        requireDirectorReal(ctx);
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is over");
        require(m.workspace() != null, Refusal.NO_REPOSITORY, id + " has no repository");
        requireText(folder, "folder");
        require(
                folder.length() <= 1000 && ABSOLUTE.matcher(folder).matches() && !climbs(folder),
                Refusal.INVALID_ARGUMENT,
                "A destination is an absolute folder, without ..");
        return view.record(ctx, id)
                .emit(new DeliverablesDestinationSet(id, folder))
                .events();
    }

    /** What Floor42 copied into the mission's folder of its destination, once, before the mission completes. */
    List<EventEnvelope> recordCopy(
            CommandContext ctx, MissionId id, String folder, List<DeliveryCopy> files, int leftOut, boolean truncated) {
        requireDirectorReal(ctx);
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is over");
        String home = destinations.get(id);
        require(home != null, Refusal.NO_DESTINATION, id + " has no destination for its deliverables");
        require(copies.get(id) == null, Refusal.OUTCOME_ALREADY_RECORDED, id + " was copied already");
        requireText(folder, "folder");
        require(inside(folder, home) && !climbs(folder), Refusal.INVALID_ARGUMENT, folder + " is not in " + home);
        List<DeliveryCopy> list = files == null ? List.of() : List.copyOf(files);
        require(list.size() <= DeliveryCopy.MAX_FILES, Refusal.INVALID_ARGUMENT, "Too many files for one record");
        require(leftOut >= 0, Refusal.INVALID_ARGUMENT, "A count is never negative");
        for (DeliveryCopy f : list) {
            require(
                    f != null && DeliveryCopy.OUTCOMES.contains(f.outcome()) && f.path() != null,
                    Refusal.INVALID_ARGUMENT,
                    "A copied file has a path and an outcome among " + DeliveryCopy.OUTCOMES);
        }
        return view.record(ctx, id)
                .emit(new DeliverablesCopied(id, folder, list, leftOut, truncated))
                .events();
    }

    private static void requireDirectorReal(CommandContext ctx) {
        require(
                ctx.actor().isDirector(),
                Refusal.FORBIDDEN,
                "Only the director decides where deliverables go: never an agent, never Floor42 on its own");
        requireReal(ctx, "SIMULATED_MISSION", "A simulated mission delivers no file");
    }

    /** A folder strictly below {@code home}: {@code home} itself, or {@code home-other}, is not. */
    private static boolean inside(String folder, String home) {
        return folder.length() > home.length() + 1
                && folder.startsWith(home)
                && (folder.charAt(home.length()) == '/' || folder.charAt(home.length()) == '\\');
    }

    private static boolean climbs(String folder) {
        for (String segment : folder.split("[\\\\/]")) if (segment.equals("..")) return true;
        return false;
    }

    void checkPullRequest(CommandContext ctx, MissionId id) {
        requirePullRequestActor(ctx);
        Mission m = view.missionFor(ctx, id);
        require(m.workspace() != null, Refusal.NO_REPOSITORY, id + " has no repository");
    }

    List<EventEnvelope> recordPullRequest(
            CommandContext ctx,
            MissionId id,
            AgentId workspace,
            String url,
            int number,
            String branch,
            String base,
            String remote,
            boolean found) {
        checkPullRequest(ctx, id);
        require(
                workspace == null || view.workspace(id, workspace).isPresent(),
                Refusal.UNKNOWN_WORKSPACE,
                workspace + " has no workspace on " + id);
        requireText(url, "url");
        require(number > 0, Refusal.INVALID_ARGUMENT, "A pull request number is positive");
        requireText(branch, "branch");
        requireText(base, "base");
        requireText(remote, "remote");
        require(!branch.equals(base), Refusal.HEAD_IS_BASE, "A pull request never merges " + branch + " into itself");
        require(
                pullRequest(id, number).isEmpty(),
                Refusal.PULL_REQUEST_RECORDED,
                "Pull request #" + number + " is already recorded for " + id);
        return view.record(ctx, id)
                .emit(new PullRequestOpened(id, workspace, url, number, branch, base, remote, found))
                .events();
    }

    List<EventEnvelope> recordPullRequestChecks(CommandContext ctx, MissionId id, int number, List<String> checks) {
        checkPullRequest(ctx, id);
        require(
                pullRequest(id, number).isPresent(),
                Refusal.UNKNOWN_PULL_REQUEST,
                "No pull request #" + number + " on " + id);
        List<String> lines = checks == null ? List.of() : List.copyOf(checks);
        require(
                lines.stream().allMatch(l -> PullRequest.Check.parse(l).isPresent()),
                Refusal.INVALID_ARGUMENT,
                "A check line is name|bucket|state");
        return view.record(ctx, id)
                .emit(new PullRequestCheckState(id, number, lines))
                .events();
    }

    List<EventEnvelope> recordPluginCommand(
            CommandContext ctx,
            String target,
            String action,
            String plugin,
            String marketplace,
            List<String> commands,
            Integer exitCode,
            boolean present,
            String version) {
        require(
                ctx.actor().isDirector(),
                Refusal.FORBIDDEN,
                "Only the director installs or uninstalls a plugin: never an agent, never Floor42 on its own");
        requireReal(ctx, "INVALID_ARGUMENT", "A plugin command is real or nothing");
        require(PLUGIN_TARGETS.contains(target), Refusal.INVALID_ARGUMENT, "A plugin target is FLOOR42 or CLAUDE");
        require(PLUGIN_ACTIONS.contains(action), Refusal.INVALID_ARGUMENT, "A plugin action is INSTALL or UNINSTALL");
        requireText(plugin, "plugin");
        requireText(marketplace, "marketplace");
        List<String> lines = commands == null ? List.of() : List.copyOf(commands);
        require(!lines.isEmpty(), Refusal.INVALID_ARGUMENT, "Say which commands ran");
        return view.record(ctx, null)
                .emit(new PluginCommandRan(target, action, plugin, marketplace, lines, exitCode, present, version))
                .events();
    }

    List<EventEnvelope> selectMissionAssets(CommandContext ctx, MissionId id, List<String> items) {
        require(
                ctx.actor().isDirector(),
                Refusal.FORBIDDEN,
                "Only the director chooses what a mission's sessions receive: never an agent, never Floor42 on its own");
        requireReal(ctx, "SIMULATED_MISSION", "A simulated mission runs no real session");
        Mission m = view.missionFor(ctx, id);
        require(!m.status.isTerminal(), Refusal.MISSION_CLOSED, id + " is over: no session will start on it");
        List<String> chosen =
                items == null ? List.of() : items.stream().distinct().toList();
        require(
                chosen.size() <= DeliveriesFacade.MAX_MISSION_ASSETS,
                Refusal.INVALID_ARGUMENT,
                "At most " + DeliveriesFacade.MAX_MISSION_ASSETS + " skills and agents for one mission");
        for (String item : chosen) {
            require(
                    item != null && ASSET_ITEM.matcher(item).matches(),
                    Refusal.INVALID_ARGUMENT,
                    "An item is <floor42|claude>:<plugin>@<marketplace>/<skill|agent>/<name>, not " + item);
        }
        return view.record(ctx, id).emit(new MissionAssetsSelected(id, chosen)).events();
    }

    /** Pull requests are outward actions: the director's only, never an agent's nor Floor42's own, and only real. */
    private static void requirePullRequestActor(CommandContext ctx) {
        require(
                ctx.actor().isDirector(),
                Refusal.FORBIDDEN,
                "Only the director opens a pull request or reads its checks: never an agent, never Floor42 on its own");
        requireReal(ctx, "SIMULATED_MISSION", "A simulated mission pushes nothing anywhere");
    }

    /** The only way this area's state changes. */
    void apply(DeliveryEvent event, EventEnvelope envelope) {
        Instant at = envelope.occurredAt();
        switch (event) {
            case PullRequestOpened e -> on(e, at);
            case PullRequestCheckState e -> on(e, at);
            case PluginCommandRan e -> on(e);
            case MissionAssetsSelected e -> on(e);
            case DeliverablesDestinationSet e -> destinations.put(e.mission(), e.folder());
            case UncommittedWorkAccepted e -> uncommittedAccepted.put(e.mission(), List.copyOf(e.worktrees()));
            case ProofRan e -> proofs.on(e, at);
            case ProofStepWitnessed e -> proofs.on(e, at);
            case ProofVerdictGiven e -> proofs.on(e, at);
            case UnprovenDeliveryAccepted e -> proofs.on(e);
            case DeliverablesCopied e ->
                copies.put(
                        e.mission(),
                        new DeliveryCopy.Done(e.folder(), List.copyOf(e.files()), e.leftOut(), e.truncated(), at));
        }
    }

    void on(PullRequestOpened e, Instant at) {
        pullRequests.add(new PullRequest(
                e.mission(),
                e.workspace(),
                e.url(),
                e.number(),
                e.branch(),
                e.base(),
                e.remote(),
                e.found(),
                at,
                List.of(),
                null));
    }

    void on(PullRequestCheckState e, Instant at) {
        for (int i = 0; i < pullRequests.size(); i++) {
            PullRequest p = pullRequests.get(i);
            if (!p.mission().equals(e.mission()) || p.number() != e.number()) continue;
            pullRequests.set(
                    i,
                    p.withChecks(
                            e.checks().stream()
                                    .flatMap(l -> PullRequest.Check.parse(l).stream())
                                    .toList(),
                            at));
        }
    }

    void on(MissionAssetsSelected e) {
        missionAssets.put(e.mission(), List.copyOf(e.items()));
    }

    void on(PluginCommandRan e) {
        // A fact of the machine, not of the company: nothing to rebuild from it.
    }
}
