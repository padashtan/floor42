package dev.floor42.domain.event;

import dev.floor42.domain.AgentId;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.TicketId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Workspaces and what the director did in them. */
public sealed interface WorkbenchEvent extends DomainEvent {

    /** Floor42 prepared an agent's workspace: a git worktree on a branch of its own, from the repository's base. */
    record WorkspacePrepared(MissionId mission, AgentId agent, String path, String branch, String baseCommit)
            implements WorkbenchEvent {}

    /**
     * The director changed a file of an agent's workspace. Agents never overwrite it silently afterwards.
     *
     * @param note nullable
     * @param diff the change as Floor42 measured it with git, possibly abbreviated
     */
    record DirectorEditedFile(MissionId mission, AgentId workspace, String path, String note, String diff)
            implements WorkbenchEvent {}

    /** The director accepted the pending changes of a workspace; Floor42 committed them on the workspace's branch. */
    record PatchAccepted(MissionId mission, AgentId workspace, String commit, String message, List<String> files)
            implements WorkbenchEvent {
        public PatchAccepted {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    /** The director rejected the pending changes; Floor42 archived them, then restored the workspace. */
    record PatchRejected(MissionId mission, AgentId workspace, String reason, String archive, List<String> files)
            implements WorkbenchEvent {
        public PatchRejected {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    /**
     * Floor42 merged, into the worktree of the developer taking a ticket, the branches where its dependencies were done
     * by others (D10). Not clean: a conflict (the merge was undone), or work that was never accepted; {@code detail}
     * says which. {@code into} is null when no developer was chosen yet.
     */
    record BranchesMerged(
            MissionId mission, TicketId ticket, AgentId into, List<String> branches, boolean clean, String detail)
            implements WorkbenchEvent {
        public BranchesMerged {
            branches = branches == null ? null : Collections.unmodifiableList(new ArrayList<>(branches));
        }
    }

    /**
     * The director gave a mission files, or committed what they had put in the project folder: a commit on the
     * director's own branch, made on their click (the one case where Floor42 writes to the director's checkout).
     */
    record DirectorFilesCommitted(MissionId mission, String commit, List<String> files) implements WorkbenchEvent {
        public DirectorFilesCommitted {
            files = files == null ? null : Collections.unmodifiableList(new ArrayList<>(files));
        }
    }

    /**
     * The director's branch, at {@code commit}, merged into an agent's worktree so the agent sees the director's files;
     * {@code clean} false when a conflict undid the merge ({@code detail} says where).
     */
    record ProjectMerged(MissionId mission, AgentId agent, String commit, boolean clean, String detail)
            implements WorkbenchEvent {}
}
