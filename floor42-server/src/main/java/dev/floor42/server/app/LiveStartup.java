package dev.floor42.server.app;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Starts the live missions with the daemon, in this order: the stall clock and the reactions listen to the journal,
 * worktrees from before the journal recorded them are recorded, then recovery resumes what the last daemon left.
 */
@ApplicationScoped
public class LiveStartup {

    private final MissionSigns signs;
    private final EventReactions reactions;
    private final AgentWorktrees worktrees;
    private final MissionRecovery recovery;
    private final MissionScheduler scheduler;
    private final DirectorMeetings meetings;
    private final boolean enabled;

    @Inject
    LiveStartup(
            MissionSigns signs,
            EventReactions reactions,
            AgentWorktrees worktrees,
            MissionRecovery recovery,
            MissionScheduler scheduler,
            DirectorMeetings meetings,
            // Off only in tests that drive the runtime by hand: nothing reacts, nothing is recovered.
            @ConfigProperty(name = "floor42.live-missions.enabled", defaultValue = "true") boolean enabled) {
        this.signs = signs;
        this.reactions = reactions;
        this.worktrees = worktrees;
        this.recovery = recovery;
        this.scheduler = scheduler;
        this.meetings = meetings;
        this.enabled = enabled;
    }

    /** Before the other start-up observers (retrospectives, staffing): an open meeting holds its agents first. */
    static final int PRIORITY = jakarta.interceptor.Interceptor.Priority.APPLICATION + 400;

    void listen(@Observes @jakarta.annotation.Priority(PRIORITY) StartupEvent ignored) {
        signs.listen();
        if (enabled) reactions.listen();
        worktrees.recordKnown();
        if (enabled) resume(r -> scheduler.fork("recover", r));
    }

    /**
     * What a restart does with what the last daemon left, in this order (the runtime scanned the session folders
     * before): a launch left without a session is recorded first, so a turn's launch is that turn's session and its cap
     * counts once (ADR-0049); then an open meeting holds its attendees again and closes its unfinished round, before
     * any work resumes (ADR-0047); then the recovery, run by {@code recoverWith}.
     */
    void resume(java.util.function.Consumer<Runnable> recoverWith) {
        recovery.countUnnamedLaunches();
        meetings.regather();
        recoverWith.accept(recovery::recover);
    }
}
