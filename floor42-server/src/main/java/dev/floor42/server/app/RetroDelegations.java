package dev.floor42.server.app;

import dev.floor42.domain.MissionId;
import dev.floor42.domain.Retro;
import dev.floor42.server.iam.Delegations;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A completed mission keeps its delegation while its retrospective is due, so the retrospective's sessions can get
 * their identity from it (IAM mode); it ends when the retrospective has ended (Retrospectives calls {@link #ended}),
 * or at start-up for a finished mission with no retrospective left to run.
 */
@ApplicationScoped
class RetroDelegations {

    private final OfficeService office;
    private final Delegations delegations;
    private final MissionScheduler scheduler;
    private final boolean active;
    /** True from the start of a shutdown: a run cut by it keeps the delegation for the restart's resume. */
    private volatile boolean stopping;

    RetroDelegations(
            OfficeService office,
            Delegations delegations,
            MissionScheduler scheduler,
            @ConfigProperty(name = "floor42.live-missions.enabled", defaultValue = "true") boolean live,
            @ConfigProperty(name = "floor42.retro.enabled", defaultValue = "true") boolean retro) {
        this.office = office;
        this.delegations = delegations;
        this.scheduler = scheduler;
        this.active = live && retro;
    }

    /** The mission completed and a retrospective follows: running sessions are cut now, the delegation stays. */
    void holdForRetro(MissionId m) {
        delegations.closeToWork(m);
    }

    /** No retrospective follows, or it has ended: cut off here at once, Keycloak told afterwards. Idempotent. */
    void release(MissionId m) {
        delegations.revokeLocally(m);
        scheduler.fork("iam-delegation-" + m, () -> delegations.revokeRemote(m));
    }

    /** A retrospective run is over; only a run cut by the daemon's own shutdown keeps the delegation. */
    void ended(MissionId m, boolean interrupted) {
        if (!(interrupted && stopping)) release(m);
    }

    void stop(@Observes @Priority(Interceptor.Priority.APPLICATION) ShutdownEvent ignored) {
        stopping = true;
    }

    /** A restart between completion and the end of the retrospective: revoke what no open retrospective needs. */
    void settle(@Observes StartupEvent ignored) {
        Set<MissionId> open = !active
                ? Set.of()
                : office.query(c ->
                        c.retros().openRetros().stream().map(Retro::mission).collect(Collectors.toSet()));
        for (MissionId m : delegations.liveMissions()) {
            boolean finished = office.query(
                    c -> c.missions().find(m).map(x -> x.status().isTerminal()).orElse(false));
            if (finished && !open.contains(m)) release(m);
        }
    }
}
