package dev.floor42.server.app;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.UncommittedWorktree;
import dev.floor42.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Work left uncommitted in a mission's worktrees, measured before the director accepts the mission (ADR-0055). A
 * worktree is CLEAN only when git answered and listed nothing; when git fails, the folder is gone or it is not a folder
 * Floor42 made, the state is UNKNOWN, never clean. Build and cache artefacts are not deliverables and are not counted.
 */
@ApplicationScoped
public class UncommittedDeliverables {

    public static final String CLEAN = "CLEAN";

    /**
     * One worktree as measured.
     *
     * @param state CLEAN, {@link UncommittedWorktree#DIRTY} or {@link UncommittedWorktree#UNKNOWN}
     * @param reason why UNKNOWN, a code of {@link UncommittedWorktree#REASONS}; null otherwise
     * @param paths the first files not committed, at most {@link UncommittedWorktree#MAX_PATHS}
     */
    public record UncommittedWorktreeState(
            String agent, String path, String state, String reason, int count, List<String> paths) {
        public UncommittedWorktreeState {
            paths = paths == null ? null : Collections.unmodifiableList(new ArrayList<>(paths));
        }

        UncommittedWorktree recorded() {
            return new UncommittedWorktree(agent, state, reason, count, paths);
        }
    }

    /**
     * Every worktree of the mission, and a token that names this exact measure: the director's acceptance carries the
     * token of what they saw, and a measure that changed since asks them again.
     */
    public record UncommittedCheck(boolean clean, String token, List<UncommittedWorktreeState> worktrees) {
        public UncommittedCheck {
            worktrees = worktrees == null ? null : Collections.unmodifiableList(new ArrayList<>(worktrees));
        }

        public List<UncommittedWorktree> notClean() {
            return worktrees.stream()
                    .filter(w -> !w.state().equals(CLEAN))
                    .map(UncommittedWorktreeState::recorded)
                    .toList();
        }
    }

    private final OfficeService office;
    private final Workspaces workspaces;
    private final MissionBranches branches;

    @Inject
    UncommittedDeliverables(OfficeService office, Workspaces workspaces, MissionBranches branches) {
        this.office = office;
        this.workspaces = workspaces;
        this.branches = branches;
    }

    /** Reads git outside the company's lock: the caller holds the mission's close lock (DeliverablesHome.closing). */
    public UncommittedCheck check(MissionId mission) {
        List<Workspace> spaces = office.query(c -> c.workshop().workspaces(mission));
        List<UncommittedWorktreeState> out = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (Workspace w : spaces) {
            Measure m = measure(Path.of(w.path()));
            List<String> files = m.files() == null ? List.of() : m.files();
            String state = m.reason() != null
                    ? UncommittedWorktree.UNKNOWN
                    : files.isEmpty() ? CLEAN : UncommittedWorktree.DIRTY;
            out.add(new UncommittedWorktreeState(
                    w.agent().value(),
                    w.path(),
                    state,
                    m.reason(),
                    files.size(),
                    files.stream().limit(UncommittedWorktree.MAX_PATHS).toList()));
            all.add(w.agent().value() + "\u0000" + state + "\u0000" + m.reason() + "\u0000"
                    + String.join("\u0000", files));
        }
        boolean clean = out.stream().allMatch(w -> w.state().equals(CLEAN));
        return new UncommittedCheck(clean, token(all), List.copyOf(out));
    }

    /** The files not committed, or why the state is unknown. */
    private record Measure(List<String> files, String reason) {}

    private Measure measure(Path dir) {
        // The real path first: only a folder under Floor42's own workspaces is read (links resolved, protected folders
        // and anything else refused before git runs).
        if (!Files.isDirectory(dir)) return new Measure(null, "WORKTREE_MISSING");
        if (!branches.madeByFloor42(dir)) return new Measure(null, "NOT_A_MISSION_WORKTREE");
        Optional<List<String>> pending = workspaces.pendingIfKnown(dir);
        if (pending.isEmpty()) return new Measure(null, "GIT_FAILED");
        List<String> files = pending.get().stream()
                .filter(p -> !BuildArtefacts.isOne(BuildArtefacts.KINDS, p))
                .sorted()
                .toList();
        return new Measure(files, null);
    }

    private static String token(List<String> lines) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String l : lines) sha.update((l + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
