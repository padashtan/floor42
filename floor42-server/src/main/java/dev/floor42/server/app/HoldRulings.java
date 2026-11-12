package dev.floor42.server.app;

import dev.floor42.domain.Actor;
import dev.floor42.domain.MissionId;
import dev.floor42.domain.OptionCode;
import dev.floor42.domain.Origin;
import dev.floor42.domain.PlanUsage;
import dev.floor42.domain.Question;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.domain.QuestionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** The director's answers to a held mission: about the usage limit, or a budget raised from the desk. */
@ApplicationScoped
public class HoldRulings {

    private static final Logger LOG = Logger.getLogger(HoldRulings.class);

    private final OfficeService office;
    private final MissionHolds holds;
    private final Dispatcher dispatcher;
    private final OfficeSettings settings;
    private final MissionScheduler scheduler;
    private final long resumeMarginSeconds;

    @Inject
    HoldRulings(
            OfficeService office,
            MissionHolds holds,
            Dispatcher dispatcher,
            OfficeSettings settings,
            MissionScheduler scheduler,
            // How long after a window's reset a held session resumes (the runtime's clock may lag Floor42's).
            @ConfigProperty(name = "floor42.plan-limit.resume-margin-seconds", defaultValue = "60")
                    long resumeMarginSeconds) {
        this.office = office;
        this.holds = holds;
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.scheduler = scheduler;
        this.resumeMarginSeconds = resumeMarginSeconds;
    }

    /** The director's answer about the usage limit: run now, run once the window resets, or drop the step. */
    void ruleOnPlanLimit(MissionId m, OptionCode answer) {
        switch (answer == null ? OptionCode.STOP_HERE : answer) {
            case RUN_ANYWAY -> {
                holds.runAnyway(m);
                dispatcher.produce(m);
            }
            case WAIT_FOR_RESET -> waitForReset(m);
            default -> LOG.infof("Mission %s: the held session is dropped", m);
        }
    }

    /**
     * The window at risk now, whatever the model: its reset is the plan's, not the session's. The mission rests until
     * then, and one wake-up of the {@link MissionScheduler} resumes it: nothing sleeps, so a restart's recovery, which
     * finds this answer in the journal and waits again, goes on to the next mission at once.
     */
    private void waitForReset(MissionId m) {
        Instant now = Instant.now();
        Instant resetsAt = office.query(c -> c.verification()
                .planLimitRisk(null, settings.planLimitThreshold(), now)
                .map(PlanUsage.Risk::resetsAt)
                .orElse(now));
        Instant resume = resetsAt.plusSeconds(resumeMarginSeconds);
        if (resume.isBefore(now)) resume = now;
        LOG.infof("Mission %s resumes after the usage window resets (%s)", m, resetsAt);
        holds.restUntil(m, resume);
        scheduler.wakeUp(wakeUpOf(m), resume, () -> {
            holds.restUntil(m, null);
            dispatcher.produce(m);
        });
    }

    /** The name of the mission's one wake-up after a plan reset. */
    static String wakeUpOf(MissionId m) {
        return "plan-reset-" + m;
    }

    /** A budget raised from the desk settles the open budget question; the held step resumes. */
    void releaseBudget(MissionId m) {
        if (office.query(c -> c.verification().budgetReached(m))) return;
        List<QuestionId> open = office.query(c -> c.desk().of(m).stream()
                .filter(q -> q.status() == QuestionStatus.OPEN && q.kind() == QuestionKind.BUDGET_REACHED)
                .map(Question::id)
                .toList());
        for (QuestionId q : open) {
            office.execute(c -> c.desk()
                    .withdraw(office.context(Actor.director(), Origin.REAL), q, "the director raised the budget"));
        }
        dispatcher.produce(m);
    }
}
