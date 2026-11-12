package dev.floor42.server.app;

import dev.floor42.domain.MissionId;
import dev.floor42.server.iam.Delegations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Moves the missions on (ADR-0019): starts every task that can run now, up to the parallel limit. Each developer
 * carries one ticket at a time in its own worktree; reviews go to a free reviewer who did not produce the ticket. When
 * a task ends the dispatcher looks again, at its mission and then at the others, which may have waited for its agents.
 */
@ApplicationScoped
public class Dispatcher {

    private final DispatchBoard board;
    private final TaskRunners runners;
    private final DependencyDesk dependencies;
    private final MissionScheduler scheduler;
    private final MissionSigns signs;
    private final Delegations delegations;
    private final boolean enabled;

    @Inject
    Dispatcher(
            DispatchBoard board,
            TaskRunners runners,
            DependencyDesk dependencies,
            MissionScheduler scheduler,
            MissionSigns signs,
            Delegations delegations,
            // Off only in tests that drive the runtime by hand.
            @ConfigProperty(name = "floor42.live-missions.enabled", defaultValue = "true") boolean enabled) {
        this.board = board;
        this.runners = runners;
        this.dependencies = dependencies;
        this.scheduler = scheduler;
        this.signs = signs;
        this.delegations = delegations;
        this.enabled = enabled;
    }

    /** The director asks Floor42 to look at a mission again: what can run starts, from what the journal says. */
    void nudge(MissionId m) {
        if (enabled) scheduler.fork("nudge-" + m, () -> produce(m));
    }

    /** The staff changed: every real mission in progress looks again. */
    void staffChanged() {
        if (!enabled) return;
        for (MissionId m : board.inProgress()) scheduler.fork("staff-" + m, () -> produce(m));
    }

    /** Keycloak is back: every mission whose session may have waited for it looks again (as after a nudge). */
    void identityBack() {
        if (!enabled) return;
        for (MissionId m : board.workable()) scheduler.fork("iam-" + m, () -> produce(m));
    }

    /** {@code pastApproval}: the plan is approved, so a delegation must exist (IAM mode). */
    boolean blocked(MissionId m, boolean pastApproval) {
        return delegations.blocks(m, pastApproval).isPresent();
    }

    /** Starts what can run on the mission now, on this thread; each task then runs on its own. */
    void produce(MissionId m) {
        // IAM mode: no agent works for a mission whose delegation is missing, revoked or expired.
        if (blocked(m, board.inProgress().contains(m))) return;
        board.requeueLeftBehind(m);
        while (true) {
            Optional<Task> picked = board.pick(m);
            if (picked.isEmpty()) return;
            Task task = picked.get();
            if (task.work() == Work.ASK_DEPENDENCY) {
                if (!askAboutDependencies(m, task)) return;
                continue;
            }
            start(m, task);
            // The committee owns the mission while it deliberates: one task, nothing beside it.
            if (task.work() == Work.COMMITTEE) return;
        }
    }

    /** Not a session: the director decides first, and the ticket waits for that answer. */
    private boolean askAboutDependencies(MissionId m, Task task) {
        try {
            return dependencies.ask(m, task.ticket());
        } finally {
            board.asked(task.ticket());
        }
    }

    private void start(MissionId m, Task task) {
        signs.mark(m);
        scheduler.fork(task.work().name().toLowerCase(Locale.ROOT) + "-" + task.ticket(), () -> run(m, task));
    }

    private void run(MissionId m, Task task) {
        try {
            boolean again = false;
            try {
                again = runners.run(m, task);
            } finally {
                signs.mark(m);
                board.free(m, task);
            }
            // Look again: the next ticket, the review, or a place freed for a ticket that waited.
            if (again) produce(m);
        } finally {
            // The agents are free again: another mission may have been waiting for them, whatever happened here.
            wakeOthers(m);
        }
    }

    /** Every other real mission in progress looks again: an agent it waited for may be free now. */
    private void wakeOthers(MissionId except) {
        for (MissionId other : board.inProgress()) {
            if (!other.equals(except)) scheduler.fork("wake-" + other, () -> produce(other));
        }
    }
}
