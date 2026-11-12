package dev.floor42.server.app;

import dev.floor42.domain.Company;
import dev.floor42.domain.Mission;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.MissionStatus;
import dev.floor42.domain.Origin;
import dev.floor42.domain.workflow.MissionInterpreter;
import dev.floor42.domain.workflow.WorkflowInterpreter.AfterEnd;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * The director convenes the retrospective of a closed mission that has none (ADR-0054): one Floor42 did not convene
 * because the mission was archived at once. Retrospective sessions are paid: they start only on the director's click,
 * never on a restore. Opened through the same path as Floor42's own convening ({@link Retrospectives#openNow}).
 */
@ApplicationScoped
public class RetroConvening {

    /** Why it cannot be convened now, as a code the screen translates; empty when it can. */
    public static final String DISABLED = "RETRO_DISABLED";

    public static final String NOT_IN_WORKFLOW = "RETRO_NOT_IN_WORKFLOW";
    public static final String NO_PARTICIPANTS = "NO_PARTICIPANTS";

    private final OfficeService office;
    private final Retrospectives retros;
    private final MissionInterpreter workflow;
    private final MissionScheduler scheduler;

    RetroConvening(
            OfficeService office, Retrospectives retros, MissionInterpreter workflow, MissionScheduler scheduler) {
        this.office = office;
        this.retros = retros;
        this.workflow = workflow;
        this.scheduler = scheduler;
    }

    /** What convening would meet now; empty when the director can convene it. Records nothing. */
    public Optional<String> refusal(MissionId m) {
        if (!retros.active()) return Optional.of(DISABLED);
        return office.query(c -> refusal(c, m));
    }

    private Optional<String> refusal(Company c, MissionId m) {
        Mission x = c.missions().find(m).orElse(null);
        if (x == null) return Optional.of("UNKNOWN_MISSION");
        if (x.origin() != Origin.REAL) return Optional.of("SIMULATED_MISSION");
        if (!x.status().isTerminal()) return Optional.of("MISSION_NOT_FINISHED");
        if (x.status() == MissionStatus.CANCELLED) return Optional.of("MISSION_CANCELLED");
        if (x.archived()) return Optional.of("MISSION_ARCHIVED");
        if (c.retros().find(m).isPresent()) return Optional.of("DUPLICATE_RETRO");
        if (!(workflow.afterEnd(c, m) instanceof AfterEnd.HoldRetro)) return Optional.of(NOT_IN_WORKFLOW);
        if (RetroCast.participants(c, m).isEmpty()) return Optional.of(NO_PARTICIPANTS);
        return Optional.empty();
    }

    /**
     * Opens it now, then runs its turns on a thread of their own. Refused with the code of {@link #refusal}; two clicks
     * at once open it once, the second meets the domain's {@code DUPLICATE_RETRO}.
     */
    public void convene(MissionId m) {
        Optional<String> refused = refusal(m);
        if (refused.isPresent()) {
            throw RefusedException.conflict(refused.get(), "The retrospective of " + m + " cannot be convened now");
        }
        if (!retros.openNow(m)) throw RefusedException.conflict(NO_PARTICIPANTS, "Nobody worked on " + m);
        scheduler.fork("retro-" + m, () -> retros.run(m));
    }
}
